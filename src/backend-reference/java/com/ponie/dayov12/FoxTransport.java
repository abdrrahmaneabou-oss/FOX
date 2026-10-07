package com.ponie.dayov12;

import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.os.*;
import android.system.*;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Routes FOX's original forwarding sockets through AWG, not the Internet.
 * Original packet handlers and replay threads are deliberately not rewritten.
 */
public final class FoxTransport {
    private static volatile FoxTransport current;
    private static volatile String state = "Disconnected";
    private final MyVpnService svc;
    private final Set<Integer> gameUids = new HashSet<>();
    private final ConnectivityManager connectivity;
    private final Map<String,Owner> owners = new LinkedHashMap<String,Owner>(256,0.75f,true) {
        private static final long serialVersionUID=1L;
        protected boolean removeEldestEntry(Map.Entry<String,Owner> e) { return size()>8192; }
    };
    private final AtomicLong legacyPackets = new AtomicLong(), awgPackets = new AtomicLong(), unknownOwners = new AtomicLong();
    private volatile boolean stopping;
    private FileDescriptor bridgeFd;
    private volatile boolean nativeStarted;
    private ParcelFileDescriptor realTun;
    private Thread inbound, monitor;
    private FileChannel out;
    private FileOutputStream outputStream;
    private static final class Owner { final int uid; final long time; Owner(int u,long t){uid=u;time=t;} }
    private static final String[] GAMES = {
        "com.dts.freefireth", "com.dts.freefiremax", "com.garena.game.kgvn",
        "com.garena.game.fconvn", "com.garena.msdk", "com.garena.game.kgtw",
        "com.garena.game.kgid", "com.garena.game.kgph", "com.vng.pubgmobile"
    };
    private FoxTransport(MyVpnService svc) {
        this.svc=svc;
        connectivity=(ConnectivityManager)svc.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        for(String pkg:GAMES) {
            try { ApplicationInfo a=svc.getPackageManager().getApplicationInfo(pkg,0); gameUids.add(a.uid); }
            catch(Exception ignored) {}
        }
    }
    public static String status() {
        FoxTransport t=current;
        return state+(t==null ? "" : "\nGame packets: "+t.legacyPackets.get()+"\nTunnel packets: "+t.awgPackets.get()+"\nUnknown owners: "+t.unknownOwners.get());
    }
    public static boolean protectInner(MyVpnService svc, DatagramSocket s) { return true; }
    public static boolean protectInner(MyVpnService svc, Socket s) { return true; }
    public static android.content.Intent command(MyVpnService svc, android.content.Intent intent) {
        if(intent==null||"android.net.VpnService".equals(intent.getAction()))
            return new android.content.Intent(svc,MyVpnService.class).setAction("com.ponie.dayov12.md.s1");
        return intent;
    }

    public static void run(MyVpnService service) {
        FoxTransport transport=new FoxTransport(service);
        synchronized(FoxTransport.class) {
            if(current!=null) { state="Already running"; return; }
            current=transport;
        }
        try { transport.start(); }
        catch(Throwable e) {
            // No exception text: third-party exceptions can contain keys/config.
            state="Tunnel startup failed ("+e.getClass().getSimpleName()+")";
            if(transport.realTun!=null&&service.run&&!transport.stopping) {
                // Once a TUN exists, retain it and drain/drop rather than leaking.
                transport.blockTransport();
                state="VPN blocked after transport failure";
                transport.drainBlocked();
            }
        } finally {
            transport.close();
            synchronized(FoxTransport.class) { if(current==transport) current=null; }
            service.active=false; MyVpnService.isVpnRunning=false;
            service.tunOutChannel=null; service.m_bcs();
            if(service.run) service.doStop();
        }
    }
    private void start() throws Exception {
        if(Build.VERSION.SDK_INT<29) throw new IOException("ANDROID_10_REQUIRED");
        state="Preparing AmneziaWG 3.1";
        byte[] plaintext=FoxConfigStore.load(svc);
        FoxAwgConfig cfg;
        try { cfg=FoxAwgConfig.parse(plaintext); } finally { Arrays.fill(plaintext,(byte)0); }
        // Resolve endpoint before establishing a VPN to avoid DNS recursion.
        String uapi=cfg.uapi();
        android.net.VpnService.Builder builder=svc.new Builder();
        builder.setSession("FOX + AmneziaWG").setMtu(cfg.mtu()).setBlocking(true).setMetered(false);
        boolean haveV4=false;
        for(String cidr:cfg.addresses()) {
            String[] pair=cidr.split("/"); InetAddress address=FoxAwgConfig.numericAddress(pair[0]);
            builder.addAddress(address,Integer.parseInt(pair[1]));
            if(address instanceof Inet4Address) {
                byte[] a=address.getAddress(); svc.tip=((a[0]&255)<<24)|((a[1]&255)<<16)|((a[2]&255)<<8)|(a[3]&255);
                haveV4=true;
            }
        }
        if(!haveV4) throw new IOException("FOX_REQUIRES_IPV4");
        for(String route:cfg.allowedIps()) {
            String[] p=route.split("/"); builder.addRoute(FoxAwgConfig.numericAddress(p[0]),Integer.parseInt(p[1]));
        }
        for(String dns:cfg.dns()) builder.addDnsServer(FoxAwgConfig.numericAddress(dns));
        // No allowed/disallowed app list: all apps, including FOX proxy sockets.
        realTun=builder.establish();
        if(realTun==null) throw new IOException("VPN_PERMISSION_REQUIRED");
        svc.tun=realTun;
        outputStream=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(realTun.getFileDescriptor()));
        out=outputStream.getChannel();
        svc.tunOutChannel=out;
        bridgeFd=new FileDescriptor(); FileDescriptor engineFd=new FileDescriptor();
        Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM,0,bridgeFd,engineFd);
        int fd;
        try(ParcelFileDescriptor dup=ParcelFileDescriptor.dup(engineFd)) { fd=dup.detachFd(); }
        Os.close(engineFd);
        int result=FoxNative.turnOn(fd,cfg.mtu(),uapi);
        uapi=null;
        if(result!=1) throw new IOException("NATIVE_CONFIG_REJECTED:"+result);
        nativeStarted=true;
        protectOuter();
        svc.active=true; MyVpnService.isVpnRunning=true; svc.m_bcs();
        state="Connecting; traffic stays inside VPN";
        inbound=new Thread(() -> receive(),"AWG-Inbound"); inbound.start();
        monitor=new Thread(() -> monitor(),"AWG-Status"); monitor.start();
        byte[] buffer=new byte[65536];
        while(svc.run&&!stopping) {
            int n=readTun(buffer);
            if(n==0) continue;
            if(n<0) break;
            if(!nativeStarted) continue;
            if(isGamePacket(buffer,n)) {
                legacyPackets.incrementAndGet();
                svc.handleIPv4Packet(buffer,n,out);
            } else {
                awgPackets.incrementAndGet();
                try { sendPacket(buffer,n); }
                catch(Exception e) { state="Transport failed; VPN blocked"; blockTransport(); }
            }
        }
    }
    private int readTun(byte[] packet) throws Exception {
        StructPollfd poll=new StructPollfd(); poll.fd=realTun.getFileDescriptor(); poll.events=(short)OsConstants.POLLIN;
        if(Os.poll(new StructPollfd[]{poll},500)==0) return 0;
        if((poll.revents&OsConstants.POLLIN)==0) return -1;
        return Os.read(poll.fd,packet,0,packet.length);
    }
    private void drainBlocked() {
        byte[] buffer=new byte[65536];
        try { while(svc.run&&!stopping) { if(readTun(buffer)<0) break; } } catch(Exception ignored) {}
    }
    private void protectOuter() throws IOException {
        for(int family:new int[]{4,6}) {
            int fd=FoxNative.socket(family);
            if(fd>=0&&!svc.protect(fd)) throw new IOException("OUTER_SOCKET_PROTECTION_FAILED");
        }
    }
    private void sendPacket(byte[] packet,int length) throws Exception {
        int n=Os.write(bridgeFd,packet,0,length);
        if(n!=length) throw new IOException("SHORT_PACKET_WRITE");
    }
    private void receive() {
        byte[] packet=new byte[65536];
        try {
            while(svc.run&&!stopping) {
                int n=Os.read(bridgeFd,packet,0,packet.length);
                if(n<=0) break;
                svc.writeTunSafe(out,Arrays.copyOf(packet,n));
            }
        } catch(Exception e) { if(!stopping) state="Inbound stopped; VPN blocked"; }
        finally { if(!stopping) blockTransport(); }
    }
    private void blockTransport() {
        // Do not close TUN/fall back to direct Internet upon tunnel failure.
        if(nativeStarted) { FoxNative.turnOff(); nativeStarted=false; }
    }
    private void monitor() {
        try {
            while(svc.run&&!stopping) {
                if(!nativeStarted) return;
                protectOuter();
                long hs=FoxNative.handshake();
                long age=System.currentTimeMillis()/1000-hs;
                state=hs>0 && age<180 ? "AmneziaWG handshake established" : "Waiting for handshake; no direct fallback";
                Thread.sleep(1000);
            }
        } catch(Exception e) { if(!stopping) { state="Transport failed; VPN blocked"; blockTransport(); } }
    }
    private boolean isGamePacket(byte[] p,int n) {
        if(n<20||(p[0]>>>4&15)!=4) return false;
        int ihl=(p[0]&15)*4;
        if(ihl<20||n<ihl+4) return false;
        // Never classify a non-initial fragment as a new transport connection.
        if(((p[6]&31)<<8|(p[7]&255))!=0) return false;
        int protocol=p[9]&255;
        if(protocol!=OsConstants.IPPROTO_UDP&&protocol!=OsConstants.IPPROTO_TCP) return false;
        int sport=((p[ihl]&255)<<8)|(p[ihl+1]&255);
        int dport=((p[ihl+2]&255)<<8)|(p[ihl+3]&255);
        long addresses=0;
        for(int i=12;i<20;i++) addresses=(addresses<<8)|(p[i]&255L);
        String id=protocol+":"+sport+":"+dport+":"+addresses;
        long now=SystemClock.elapsedRealtime();
        Owner cached=owners.get(id);
        int uid;
        if(cached!=null&&now-cached.time<30000) uid=cached.uid;
        else {
            try {
                InetSocketAddress local=new InetSocketAddress(InetAddress.getByAddress(Arrays.copyOfRange(p,12,16)),sport);
                InetSocketAddress remote=new InetSocketAddress(InetAddress.getByAddress(Arrays.copyOfRange(p,16,20)),dport);
                uid=connectivity.getConnectionOwnerUid(protocol,local,remote);
            } catch(Exception e) { uid=-1; }
            if(uid<0) { unknownOwners.incrementAndGet(); return false; }
            owners.put(id,new Owner(uid,now));
        }
        // Essential anti-recursion guard. FOX-created sockets go straight to AWG.
        if(uid==android.os.Process.myUid()) return false;
        return gameUids.contains(uid);
    }
    public static void stop(MyVpnService service) {
        FoxTransport t=current;
        if(t!=null&&t.svc==service) { state="Disconnected"; t.close(); }
    }
    private synchronized void close() {
        if(stopping) return;
        stopping=true;
        if(monitor!=null) monitor.interrupt();
        if(bridgeFd!=null) {
            try { Os.shutdown(bridgeFd,OsConstants.SHUT_RDWR); } catch(Exception ignored) {}
            try { Os.close(bridgeFd); } catch(Exception ignored) {}
        }
        if(nativeStarted) { FoxNative.turnOff(); nativeStarted=false; }
        if(outputStream!=null) { try { outputStream.close(); } catch(Exception ignored) {} }
        if(realTun!=null) { try { realTun.close(); } catch(Exception ignored) {} }
        for(Thread worker:new Thread[]{inbound,monitor}) {
            if(worker!=null&&worker!=Thread.currentThread()) {
                try { worker.join(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
