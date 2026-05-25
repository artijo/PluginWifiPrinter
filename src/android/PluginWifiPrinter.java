package com.yourcompany.pluginwifiprinter;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.*;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.format.Formatter;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.posprinter.IConnectListener;
import net.posprinter.IDeviceConnection;
import net.posprinter.POSConnect;
import net.posprinter.POSConst;
import net.posprinter.POSPrinter;

import com.epson.epos2.Epos2Exception;
import com.epson.epos2.discovery.DeviceInfo;
import com.epson.epos2.discovery.Discovery;
import com.epson.epos2.discovery.DiscoveryListener;
import com.epson.epos2.discovery.FilterOption;
import com.epson.epos2.printer.Printer;
import com.epson.epos2.printer.PrinterStatusInfo;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PluginWifiPrinter extends CordovaPlugin {

    private static final String TAG = "PluginWifiPrinter";
    private CallbackContext scanCallbackContext;

    private StringBuilder base64Buffer = new StringBuilder();

    // ใหม่ 25/2/2026
    private static final Map<String, Object> printerLocks = new ConcurrentHashMap<>();

    /**
     * ePOS2 เปิด USB ได้ทีละ session — ถ้าใช้ล็อกตาม target คนละคีย์ระหว่าง "USB:" กับ "USB:/dev/..."
     * งานจะซ้อนกันแล้ว connect ได้ ERR_ILLEGAL / ERR_IN_USE
     */
    private static final Object EPSON_USB_GLOBAL_LOCK = new Object();

    /**
     * target ที่ Printer.connect สำเร็จล่าสุด (ต่อ process) — ลดรอบ Discovery และไม่ย่อเป็นแค่ "USB:"
     * เมื่อ Discovery คืน USB:/dev/... แต่ normalize เดิมทำให้กลายเป็น USB: แล้ว connect ช้า/ล้ม
     */
    private static volatile String sLastGoodEpsonUsbConnectTarget;

    /**
     * Persistent Epson USB Printer instance — เก็บ connection ไว้ข้าม job
     * แก้ปัญหา TM-T82II ERR_CONNECT ครั้งที่ 2+ (USB handle ยังค้างหลัง disconnect)
     */
    private static volatile Printer sEpsonUsbPrinterInstance = null;
    private static volatile String  sEpsonUsbPrinterTarget   = null;
    private static volatile int     sEpsonUsbPrinterSeries   = -1;

    private volatile boolean posSdkInited;

    private static final IConnectListener EMPTY_POS_LISTENER =
            (code, connInfo, msg) ->
                    Log.d(TAG, "POSConnect listener: code=" + code + " info=" + connInfo + " msg=" + msg);

    private void ensurePosSdkInit() {
        if (!posSdkInited) {
            synchronized (PluginWifiPrinter.class) {
                if (!posSdkInited) {
                    POSConnect.init(cordova.getActivity().getApplicationContext());
                    posSdkInited = true;
                }
            }
        }
    }

    /** Xprinter SDK ethernet address: plain IP or IP:PORT when port != 9100 */
    private static String ethernetConnectAddress(String host, int port) {
        if (port <= 0 || port == 9100) {
            return host;
        }
        return host + ":" + port;
    }

    /**
     * Parse host and port from {@code ip} or {@code host:port} (IPv4 LAN).
     */
    private static String[] splitHostPort(String ipOrBoth, int defaultPort) {
        String host = ipOrBoth != null ? ipOrBoth.trim() : "";
        int port = defaultPort;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && colon < host.length() - 1) {
            String p = host.substring(colon + 1);
            boolean numeric = true;
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) {
                    numeric = false;
                    break;
                }
            }
            if (numeric) {
                try {
                    port = Integer.parseInt(p);
                    host = host.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // keep whole string as host
                }
            }
        }
        return new String[]{host, Integer.toString(port)};
    }

    /**
     * Epson ePOS2 ต้องการ Application context ตามเอกสาร — ห้ามส่ง Activity เข้า Printer
     */
    private Context getAppCtxForEpson() {
        Activity a = cordova.getActivity();
        if (a != null) {
            return a.getApplicationContext();
        }
        Context c = cordova.getContext();
        return c != null ? c.getApplicationContext() : null;
    }

    @Override
    public void pluginInitialize() {
        super.pluginInitialize();
        // ห้าม ensurePosSdkInit() ที่นี่ — Xprinter POSConnect แย่ง USB กับ ePOS2 ทำให้พิมพ์ Epson รอบสองได้ ERR_CONNECT
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "execute called with action: " + action);

        if ("scanNetworkDevices".equals(action)) {
            this.scanCallbackContext = callbackContext;
            scanDevicesOnNetwork();
            return true;

        } else if ("scanIpList".equals(action)) {
            JSONArray ipList = args.getJSONArray(0);
            scanIpList(ipList, callbackContext);
            return true;
        } else if ("printBase64ImageToXprinter".equals(action)) {
            String ip = args.getString(0);
            String base64Data = args.getString(1);
            int paperWidth = args.optInt(2, 576);
            printBase64ImageToXprinter(ip, base64Data, paperWidth, callbackContext);
            return true;

        } else if ("printTextAsImage".equals(action)) {
            String ip = args.getString(0);
            String text = args.getString(1);
            printTextAsImage(ip, text, callbackContext);
            return true;

        } else if ("printHtmlBill".equals(action)) {
            String ip = args.getString(0);
            String html = args.getString(1);
            printHtmlAsImage(ip, html, callbackContext);
            return true;

        } else if ("clearPrinterQueue".equals(action)) {
            String ip = args.getString(0);
            clearPrinterQueue(ip, callbackContext);
            return true;
        } else if ("checkIpWithPort".equals(action)) {
            String ipWithPort = args.getString(0);
            checkIpWithPort(ipWithPort, callbackContext);
            return true;
        } else if ("openCashDrawer".equals(action)) {
            String ipAndPort = args.getString(0);
            openCashDrawer(ipAndPort, callbackContext);
            return true;
        } else if ("listUsbPrinters".equals(action)) {
            String brand = args.optString(0, "all");
            listUsbPrinters(brand, callbackContext);
            return true;
        } else if ("requestUsbPermission".equals(action)) {
            String target = args.getString(0);
            requestUsbPermission(target, callbackContext);
            return true;
        } else if ("printBase64ImageToUsb".equals(action)) {
            String brand = args.getString(0);
            String target = args.getString(1);
            String base64Data = args.getString(2);
            int paperWidth = args.optInt(3, 576);
            String modelStr = args.optString(4, "");
            printBase64ImageToUsb(brand, target, base64Data, paperWidth, modelStr, callbackContext);
            return true;
        } else if ("openCashDrawerUsb".equals(action)) {
            String brand = args.getString(0);
            String target = args.getString(1);
            String modelStr = args.optString(2, "");
            openCashDrawerUsb(brand, target, modelStr, callbackContext);
            return true;
        } else if ("clearPrinterQueueUsb".equals(action)) {
            String brand = args.getString(0);
            String target = args.getString(1);
            String modelStr = args.optString(2, "");
            clearPrinterQueueUsb(brand, target, modelStr, callbackContext);
            return true;
        }

        Log.w(TAG, "Unknown action: " + action);
        return false;
    }

    // เปิดลิ้นชักเก็บเงิน — Xprinter POS SDK (LAN/TCP)
    private void openCashDrawer(String ipAndPort, CallbackContext callbackContext) {
        if (ipAndPort == null || ipAndPort.trim().isEmpty()) {
            callbackContext.error("ไม่มี IP หรือ Port");
            return;
        }

        final String lockKey = ipAndPort.trim();
        final String[] hp = splitHostPort(lockKey, 9100);
        final String host = hp[0];
        final int port = Integer.parseInt(hp[1]);

        if (host.isEmpty()) {
            callbackContext.error("ไม่มี IP หรือ Port");
            return;
        }

        new Thread(() -> {
            synchronized (printerLocks.computeIfAbsent(lockKey, k -> new Object())) {
                ensurePosSdkInit();
                IDeviceConnection conn = null;
                try {
                    conn = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET);
                    String addr = ethernetConnectAddress(host, port);

                    if (!conn.connectSync(addr, EMPTY_POS_LISTENER)) {
                        callbackContext.error("เชื่อมต่อเครื่องพิมพ์ไม่สำเร็จ");
                        return;
                    }

                    pulseXprinterCashDrawer(new POSPrinter(conn));
                    sleepQuiet(200);
                    callbackContext.success("✅ เปิดลิ้นชักเก็บเงินสำเร็จ");
                    Log.i(TAG, "✅ openCashDrawer (SDK) " + addr);
                } catch (Exception e) {
                    Log.e(TAG, "❌ เปิดลิ้นชักล้มเหลว: " + e.getMessage(), e);
                    callbackContext.error("เปิดลิ้นชักเก็บเงินล้มเหลว: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        try {
                            conn.closeSync();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }).start();
    }

    // -- สแกน ip ทั้งหมดที่เชื่อมต่อกับ wifi นี้
    private void scanDevicesOnNetwork() {
        new Thread(() -> {
            List<String> printerDevices = new ArrayList<>();

            try {
                WifiManager wifiManager = (WifiManager) this.cordova.getActivity()
                        .getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);

                int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
                String ip = Formatter.formatIpAddress(ipAddress);
                Log.d(TAG, "Device IP: " + ip);

                String subnet = ip.substring(0, ip.lastIndexOf('.') + 1);
                Log.d(TAG, "Subnet: " + subnet);

                for (int i = 1; i < 255; i++) {
                    String host = subnet + i;
                    InetAddress inet = InetAddress.getByName(host);

                    if (inet.isReachable(200)) {
                        Log.d(TAG, "Host reachable: " + host);

                        if (isPrinter(host)) {
                            Log.d(TAG, "Printer found at: " + host);
                            printerDevices.add(host);
                        }
                    }
                }

                JSONObject result = new JSONObject();
                result.put("printers", new JSONArray(printerDevices));
                scanCallbackContext.success(result.toString());

                Log.d(TAG, "Scan completed. Printers found: " + printerDevices.size());

            } catch (Exception e) {
                Log.e(TAG, "Error scanning network: " + e.getMessage(), e);
                scanCallbackContext.error("Scan error: " + e.getMessage());
            }
        }).start();
    }

    // ----เช็คแค่ip ที่ส่งไป
    private void scanIpList(JSONArray ips, CallbackContext callbackContext) {
        new Thread(() -> {
            List<String> printerDevices = new ArrayList<>();

            try {
                for (int i = 0; i < ips.length(); i++) {
                    String ip = ips.getString(i);
                    Log.d(TAG, "Checking IP: " + ip);

                    if (isPrinter(ip)) {
                        Log.d(TAG, "Printer found at: " + ip);
                        printerDevices.add(ip);
                    }
                }

                JSONObject result = new JSONObject();
                result.put("printers", new JSONArray(printerDevices));
                callbackContext.success(result.toString());

                Log.d(TAG, "Scan completed. Printers found: " + printerDevices.size());

            } catch (Exception e) {
                Log.e(TAG, "Error scanning list: " + e.getMessage(), e);
                callbackContext.error("Scan error: " + e.getMessage());
            }
        }).start();
    }

    // ----เช็คแค่ip:port ที่ส่งไป
    private void checkIpWithPort(String ipWithPort, CallbackContext callbackContext) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Checking IP with port: " + ipWithPort);

                String[] parts = ipWithPort.split(":");
                String ip = parts[0];
                int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9100; // default

                if (isPrinter(ip, port)) {
                    callbackContext.success("Printer found at: " + ipWithPort);
                } else {
                    callbackContext.error("No printer at: " + ipWithPort);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error checking IP with port: " + e.getMessage(), e);
                callbackContext.error("Error: " + e.getMessage());
            }
        }).start();
    }

    // -------

    // เช็คว่าอุปกรณ์คือเครื่องพิมพ์หรือไม่ (ผ่านพอร์ต 9100)
    private boolean isPrinter(String ip) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, 9100), 2000);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Attempt " + (attempt + 1) + " failed for " + ip);
            }
        }
        return false;
    }

    /**
     * ตรวจสอบว่า IP:PORT ที่ให้มาเป็นเครื่องพิมพ์หรือไม่
     * พยายามเชื่อมต่อสูงสุด 3 ครั้ง ด้วย timeout 2 วินาที
     *
     * @param ip   ที่อยู่ IP ของเครื่อง
     * @param port พอร์ตที่ต้องการเช็ค
     * @return true ถ้าสามารถเชื่อมต่อได้, false ถ้าเชื่อมต่อไม่ได้
     */
    private boolean isPrinter(String ip, int port) {
        final int MAX_ATTEMPTS = 3;
        final int TIMEOUT_MS = 2000;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (Socket socket = new Socket()) {
                Log.d(TAG, String.format("🔎 Attempt %d: Connecting to %s:%d", attempt, ip, port));

                socket.connect(new InetSocketAddress(ip, port), TIMEOUT_MS);

                Log.d(TAG, String.format("✅ Connection successful: %s:%d", ip, port));
                return true;

            } catch (Exception e) {
                Log.w(TAG, String.format("⚠️ Attempt %d failed for %s:%d - %s",
                        attempt, ip, port, e.getMessage()));
            }
        }

        Log.d(TAG, String.format("❌ Unable to connect to %s:%d after %d attempts", ip, port, MAX_ATTEMPTS));
        return false;
    }

    // เช็ค ping ใหม่เมื่อหลุด
    private void waitForPrinterAndRetry(String ip, Bitmap bitmap, CallbackContext callbackContext) {
        new Thread(() -> {
            int maxRetries = 20;
            int delayMs = 1000;

            for (int i = 0; i < maxRetries; i++) {
                // Log.i(TAG, "🔄 Ping attempt " + (i + 1) + " to " + ip);

                if (ping(ip)) {
                    // Log.i(TAG, "✅ Printer at " + ip + " is reachable. Retrying print.");
                    if (trySendBitmap(ip, bitmap)) {
                        callbackContext.success("Printed after retry to: " + ip);
                        return;
                    }
                }

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                }
            }

            callbackContext.error("Printer at " + ip + " did not recover in time.");
        }).start();
    }

    private boolean ping(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isReachable(500); // 500ms timeout
        } catch (IOException e) {
            Log.w(TAG, "Ping to " + ip + " failed: " + e.getMessage());
            return false;
        }
    }

    private boolean trySendBitmap(String ip, Bitmap bitmap) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(ip, 9100), 1000);
            OutputStream out = socket.getOutputStream();

            printBitmapAsRaster(bitmap, out);

            out.flush();
            out.close();
            socket.close();

            // Log.i(TAG, "✅ Printed successfully to " + ip);
            return true;

        } catch (Exception e) {
            Log.w(TAG, "Failed to print to " + ip + ": " + e.getMessage());
            return false;
        }
    }

    // Xprinter official Android SDK 3.2.0 — TCP/LAN (ESC/POS raster via POSPrinter)
    private void printBase64ImageToXprinter(
            String ip, String base64Image, int paperWidth, CallbackContext callbackContext) {

        if (base64Image == null || base64Image.trim().isEmpty()) {
            callbackContext.error("❌ ข้อมูล Base64 ว่างเปล่า");
            return;
        }
        if (paperWidth <= 0) {
            paperWidth = 576;
        }
        final int widthPx = paperWidth;

        Object lock = printerLocks.computeIfAbsent(ip != null ? ip : "", k -> new Object());

        new Thread(() -> {
            synchronized (lock) {
                ensurePosSdkInit();
                Bitmap originalBitmap = null;
                Bitmap processedBitmap = null;
                IDeviceConnection conn = null;

                try {
                    String[] hp = splitHostPort(ip, 9100);
                    String host = hp[0];
                    int port = Integer.parseInt(hp[1]);

                    if (host.isEmpty()) {
                        callbackContext.error("❌ ไม่มี IP");
                        return;
                    }

                    byte[] decoded = Base64.decode(base64Image.replaceAll("\\s", ""), Base64.NO_WRAP);
                    originalBitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);

                    if (originalBitmap == null) {
                        callbackContext.error("❌ ไม่สามารถแปลง Base64 เป็นรูปภาพได้");
                        return;
                    }

                    processedBitmap = resizeBitmap(originalBitmap, widthPx);
                    processedBitmap = toBlackAndWhiteDither(processedBitmap);

                    conn = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET);
                    String addr = ethernetConnectAddress(host, port);

                    if (!conn.connectSync(addr, EMPTY_POS_LISTENER)) {
                        callbackContext.error("❌ เชื่อมต่อเครื่องพิมพ์ไม่สำเร็จ");
                        return;
                    }

                    POSPrinter printer = new POSPrinter(conn);
                    printer.initializePrinter()
                            .printBitmap(processedBitmap, POSConst.ALIGNMENT_CENTER, widthPx)
                            .feedLine(3)
                            .cutHalfAndFeed(1);

                    // ===== หลังบรรทัดนี้ถือว่าข้อมูลถูก queue ไปฝั่ง SDK แล้ว =====
                    // Xprinter POS SDK เป็น async — printBitmap/cutHalfAndFeed แค่ enqueue command
                    // จาก scenario นี้:
                    //  - ถ้าเรา error หลังจุดนี้ → JS retry → พิมพ์เบิ้ลซ้ำ
                    //  - ถ้าเรารีบ success โดยไม่ flush → ปิด socket เร็วเกิน → พิมพ์ขาด
                    // ดังนั้น: รอ flush ก่อน แล้ว "ตอบ success เสมอ" หลังจุดนี้ (close error → log warn เท่านั้น)
                    try {
                        // เผื่อเวลาให้ SDK ส่งข้อมูลออกจาก buffer
                        // (Xprinter/EPSON LAN บิตแมปขนาดทั่วไป ส่ง ~200-400ms)
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }

                    try {
                        conn.closeSync();
                    } catch (Exception closeEx) {
                        // ปิด socket throw หลังข้อมูลส่งไปแล้ว — ไม่ใช่ error ที่ผู้ใช้ต้องเห็น
                        Log.w(TAG, "closeSync warning (data already sent): " + closeEx.getMessage());
                    } finally {
                        conn = null; // กัน finally ด้านนอกปิดซ้ำ
                    }

                    callbackContext.success("1");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Print error (Xprinter SDK): " + e.getMessage(), e);
                    callbackContext.error("❌ ข้อผิดพลาด: " + e.getMessage());
                } finally {
                    if (processedBitmap != null && !processedBitmap.isRecycled()) {
                        try {
                            processedBitmap.recycle();
                        } catch (Exception ignored) {}
                    }
                    if (originalBitmap != null && !originalBitmap.isRecycled()) {
                        try {
                            originalBitmap.recycle();
                        } catch (Exception ignored) {}
                    }
                    if (conn != null) {
                        try {
                            conn.closeSync();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }).start();
    }
    // ----------------------------------เวอร์เก่า------------------------------------
    // private void printBase64ImageToXprinter(String ip, String base64Image, CallbackContext callbackContext) {
    //     new Thread(() -> {
    //         if (base64Image == null || base64Image.trim().isEmpty()) {
    //             callbackContext.error("❌ ข้อมูล Base64 ว่างเปล่า");
    //             return;
    //         }
    //         base64Buffer.setLength(0);
    //         base64Buffer.append(base64Image);

    //         Bitmap originalBitmap = null;
    //         Socket socket = null;

    //         try {
    //             // ✅ เช็กว่าเป็น Epson ไหม
    //             boolean isEpson = ip.toLowerCase().contains("epson");
    //             // Log.i(TAG, "🔍 Printer type check: " + (isEpson ? "Epson" : "Xprinter/Other"));

    //             // Log.i(TAG, "🔍 เริ่มแปลงข้อมูล Base64 เป็นรูปภาพ...");
    //             byte[] decoded = Base64.decode(base64Image.replaceAll("\\s", ""), Base64.NO_WRAP);
    //             originalBitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);

    //             if (originalBitmap == null) {
    //                 callbackContext.error("❌ ไม่สามารถแปลง Base64 เป็นรูปภาพได้");
    //                 return;
    //             }

    //             // Log.i(TAG, "✅ แปลงรูปภาพสำเร็จ กำลังปรับขนาดและแปลงเป็นขาวดำ...");
    //             Bitmap processedBitmap = resizeBitmap(originalBitmap, 576);
    //             processedBitmap = toBlackAndWhiteDither(processedBitmap);

    //             List<Bitmap> chunks = splitBitmap(processedBitmap, 1000);
    //             // Log.i(TAG, "📄 แบ่งรูปภาพเป็น " + chunks.size() + " ชิ้น");

    //             socket = connectToPrinter(ip);
    //             // Log.i(TAG, "🔗 เชื่อมต่อกับเครื่องพิมพ์ที่ IP: " + ip);

    //             OutputStream out = socket.getOutputStream();
    //             InputStream in = socket.getInputStream();

    //             boolean allChunksSent = true;

    //             // ส่งทุก chunk พร้อม retry
    //             for (int i = 0; i < chunks.size(); i++) {
    //                 Bitmap chunk = chunks.get(i);
    //                 // Log.i(TAG, String.format("🖨️ ส่งชิ้นที่ %d/%d", i + 1, chunks.size()));

    //                 boolean sent = false;
    //                 int retryMax = 3;
    //                 int retryDelayMs = 1000;

    //                 for (int attempt = 1; attempt <= retryMax; attempt++) {
    //                     if (sendBitmapChunkToStream(chunk, out)) {
    //                         // Log.i(TAG, String.format("✅ ชิ้น %d ส่งสำเร็จในครั้งที่ %d", i + 1, attempt));
    //                         sent = true;
    //                         break;
    //                     } else {
    //                         Log.w(TAG, String.format(
    //                                 "⚠️ ชิ้น %d ส่งไม่สำเร็จ (ครั้งที่ %d) รอ %d มิลลิวินาทีแล้วลองใหม่",
    //                                 i + 1, attempt, retryDelayMs));
    //                         Thread.sleep(retryDelayMs);
    //                     }
    //                 }

    //                 chunk.recycle();

    //                 if (!sent) {
    //                     allChunksSent = false;
    //                     break;
    //                 }
    //             }

    //             out.flush();

    //             // ✅ Epson กับ Xprinter อาจใช้ flow ไม่เหมือนกัน
    //             if (allChunksSent) {
    //                 if (isEpson) {
    //                     // Epson: ไม่ต้องรอสถานะ แค่ตัดกระดาษพอ
    //                     cutPaper(out);
    //                     callbackContext.success("1");
    //                 } else {
    //                     // Xprinter: รอสถานะเครื่องก่อนตัด
    //                     // Log.i(TAG, "⏳ รอสถานะเครื่องพิมพ์ (Xprinter)...");
    //                     if (waitForPrinterReady(out, in, 2000, isEpson)) {
    //                         cutPaper(out);
    //                         callbackContext.success("1");
    //                     } else {
    //                         cutPaper(out);
    //                         callbackContext.error("⚠️ Printer timeout แต่ตัดกระดาษแล้ว");
    //                     }
    //                 }
    //             } else {
    //                 cutPaper(out);
    //                 callbackContext.error("❌ พิมพ์ไม่ครบทุกชิ้น");
    //             }

    //         } catch (Exception e) {
    //             Log.e(TAG, "❌ เกิดข้อผิดพลาดระหว่างพิมพ์: " + e.getMessage(), e);
    //             callbackContext.error("❌ ข้อผิดพลาด: " + e.getMessage());
    //         } finally {
    //             if (originalBitmap != null && !originalBitmap.isRecycled()) {
    //                 originalBitmap.recycle();
    //             }
    //             base64Buffer.setLength(0);

    //             try {
    //                 if (socket != null && !socket.isClosed()) {
    //                     socket.close();
    //                     // Log.i(TAG, "🔌 ปิดการเชื่อมต่อกับเครื่องพิมพ์แล้ว");
    //                 }
    //             } catch (IOException ignore) {
    //             }
    //         }
    //     }).start();
    // }

    /*----------------------------------------------------------------------- */

    private void printTextAsImage(String ip, String text, CallbackContext callbackContext) {
        new Thread(() -> {
            try {
                int width = 576, padding = 10, textWidth = width - 2 * padding;
                TextPaint paint = new TextPaint();
                paint.setColor(Color.BLACK);
                paint.setTextSize(24f);
                paint.setAntiAlias(true);

                StaticLayout staticLayout = StaticLayout.Builder
                        .obtain(text, 0, text.length(), paint, textWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0, 1)
                        .setIncludePad(false)
                        .build();

                int height = staticLayout.getHeight() + padding * 2;
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);
                canvas.translate(padding, padding);
                staticLayout.draw(canvas);

                sendBitmapAsEscpos(ip, bitmap, callbackContext);
            } catch (Exception e) {
                Log.e(TAG, "PrintTextAsImage error: " + e.getMessage(), e);
                callbackContext.error("PrintTextAsImage error: " + e.getMessage());
            }
        }).start();
    }

    private void printHtmlAsImage(String ip, String html, CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
            WebView webView = new WebView(cordova.getActivity());
            webView.setDrawingCacheEnabled(true);
            webView.setBackgroundColor(Color.WHITE);

            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

            webView.setWebViewClient(new WebViewClient() {
                public void onPageFinished(WebView view, String url) {
                    view.measure(
                            View.MeasureSpec.makeMeasureSpec(576, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());

                    Bitmap bitmap = Bitmap.createBitmap(view.getMeasuredWidth(), view.getMeasuredHeight(),
                            Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    view.draw(canvas);

                    sendBitmapAsEscpos(ip, bitmap, callbackContext);
                }
            });
        });
    }

    // --- เคลียร์คิว — ESC @ via Xprinter POS SDK
    private void clearPrinterQueue(String ip, CallbackContext callbackContext) {
        if (ip == null || ip.trim().isEmpty()) {
            callbackContext.error("IP ว่าง");
            return;
        }

        final String lockKey = ip.trim();
        final String[] hp = splitHostPort(lockKey, 9100);
        final String host = hp[0];
        final int port = Integer.parseInt(hp[1]);

        new Thread(() -> {
            synchronized (printerLocks.computeIfAbsent(lockKey, k -> new Object())) {
                ensurePosSdkInit();
                IDeviceConnection conn = null;
                try {
                    if (host.isEmpty()) {
                        callbackContext.error("IP ว่าง");
                        return;
                    }
                    conn = POSConnect.createDevice(POSConnect.DEVICE_TYPE_ETHERNET);
                    String addr = ethernetConnectAddress(host, port);

                    if (!conn.connectSync(addr, EMPTY_POS_LISTENER)) {
                        callbackContext.error("❌ Failed to clear queue");
                        return;
                    }

                    new POSPrinter(conn).initializePrinter();
                    callbackContext.success("✅ เคลียร์คิวเครื่องพิมพ์ที่ " + lockKey + " แล้ว");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to clear queue: " + e.getMessage(), e);
                    callbackContext.error("❌ Failed to clear queue: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        try {
                            conn.closeSync();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }).start();
    }

    // ------------------------------------------------เตรียมรายละเอียดก่อนปริ้น--------------------------------------------------------//
    /** แยกออกมาเป็นเมทอดเล็ก ๆ */
    private Socket connectToPrinter(String ip) throws IOException {
        Log.i(TAG, "🔗 เชื่อมต่อกับเครื่องพิมพ์ที่ " + ip);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(ip, 9100), 3000);
        Log.i(TAG, "✅ เชื่อมต่อเครื่องพิมพ์สำเร็จ");
        return socket;
    }

    private boolean waitForPrinterReady(OutputStream out, InputStream in, int timeoutMs, boolean isEpson) {
        try {
            if (isEpson) {
                // Epson: ไม่ตอบ DLE EOT 1 → รอหน่วงเวลาแทน
                // Log.i(TAG, "⌛ [Epson] รอ buffer ประมวลผล...");
                Thread.sleep(timeoutMs); // รอแค่เวลา
                return true;
            }

            // Xprinter: ใช้ DLE EOT 1
            byte[] statusCmd = new byte[] { 0x10, 0x04, 0x01 }; // DLE EOT 1
            byte[] response = new byte[1];
            long startTime = System.currentTimeMillis();

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                out.write(statusCmd);
                out.flush();

                if (in.read(response) == -1) {
                    Log.e(TAG, "📄 ไม่ได้รับข้อมูลจากเครื่องพิมพ์");
                    break;
                }

                // Log.i(TAG, "🖨 ไบต์สถานะ: 0x" + Integer.toHexString(response[0] & 0xff));

                // ตัวอย่างเช็คว่า ready
                if ((response[0] & 0x12) == 0x12) {
                    return true;
                }

                Thread.sleep(1000); // รอแล้วเช็คใหม่
            }
        } catch (Exception e) {
            Log.e(TAG, "เกิดข้อผิดพลาดขณะรอเครื่องพิมพ์", e);
        }
        return false;
    }

    private List<Bitmap> splitBitmap(Bitmap original, int maxHeight) {
        List<Bitmap> chunks = new ArrayList<>();
        int width = original.getWidth();
        int height = original.getHeight();

        // ลด chunk สูงสุดลง เช่น 500 px
        int safeMaxHeight = Math.min(maxHeight, 500);

        for (int y = 0; y < height; y += safeMaxHeight) {
            int chunkHeight = Math.min(safeMaxHeight, height - y);
            Bitmap chunk = Bitmap.createBitmap(original, 0, y, width, chunkHeight);
            chunks.add(chunk);
        }

        return chunks;
    }

    // ตัดกระดาษ
    private void cutPaper(OutputStream out) throws IOException {
        out.write(0x1D);
        out.write(0x56);
        out.write(0x41);
        out.write(0x10);
        out.flush();
    }

    private boolean sendBitmapChunk(String ip, Bitmap bitmap) {
        int maxRetry = 3;
        int retryDelayMs = 1000;

        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                // หน่วงก่อนลองต่อใหม่
                if (attempt > 1) {
                    // Log.i(TAG, "⏳ Waiting before retrying chunk...");
                    Thread.sleep(retryDelayMs);
                }

                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(ip, 9100), 3000);
                OutputStream out = socket.getOutputStream();

                printBitmapAsRaster(bitmap, out);

                out.flush();
                out.close();
                socket.close();

                // Log.i(TAG, "✅ Printed chunk successfully on attempt " + attempt);
                return true;

            } catch (Exception e) {
                Log.e(TAG, "Failed to print chunk to " + ip + ": " + e.getMessage(), e);
                Log.w(TAG, "⚠️ Failed to print chunk on attempt " + attempt);
            }
        }
        return false;
    }

    private boolean isPrinterReady(String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, 9100), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean sendBitmapChunkToStream(Bitmap bitmap, OutputStream out) {
        final int retryMax = 3;
        final int retryDelayMs = 200;
        boolean sent = false;

        for (int attempt = 1; attempt <= retryMax; attempt++) {
            try {
                printBitmapAsRaster(bitmap, out); // ส่ง chunk
                // ⚠️ ไม่ flush ทุกชิ้น ลด delay
                sent = true;
                // Log.i(TAG, "✅ Chunk ส่งสำเร็จ (ครั้งที่ " + attempt + ")");
                break;
            } catch (Exception e) {
                Log.w(TAG, "⚠️ Chunk ส่งไม่สำเร็จ (ครั้งที่ " + attempt + "), รอ " + retryDelayMs + "ms");
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ignored) {
                }
            }
        }

        if (!sent) {
            Log.e(TAG, "❌ ไม่สามารถส่ง chunk ได้หลังจาก " + retryMax + " ครั้ง");
        }

        return sent;
    }

    private Bitmap toBlackAndWhiteDither(Bitmap original) {
        int width  = original.getWidth();
        int height = original.getHeight();

        // ดึง pixels ทั้งหมดในครั้งเดียว (เร็วกว่า getPixel loop ~20x — ลด JNI overhead)
        int[] pixels = new int[width * height];
        original.getPixels(pixels, 0, width, 0, 0, width, height);

        // grayscale float array (float ให้ error accumulation แม่นยำกว่า int)
        float[] gray = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            gray[i] = ((p >> 16 & 0xFF) + (p >> 8 & 0xFF) + (p & 0xFF)) / 3f;
        }

        // Floyd–Steinberg Dithering (เขียนผลลัพธ์กลับลง pixels[] โดยตรง)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int   idx    = y * width + x;
                float oldVal = gray[idx];
                int   newVal = oldVal < 160 ? 0 : 255;
                float error  = oldVal - newVal;
                pixels[idx]  = (newVal == 0) ? 0xFF000000 : 0xFFFFFFFF;
                if (x + 1 < width)
                    gray[idx + 1]           += error * 7f / 16f;
                if (y + 1 < height) {
                    if (x > 0)
                        gray[idx + width - 1] += error * 3f / 16f;
                    gray[idx + width]         += error * 5f / 16f;
                    if (x + 1 < width)
                        gray[idx + width + 1] += error * 1f / 16f;
                }
            }
        }

        Bitmap bwBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bwBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bwBitmap;
    }

    private Bitmap resizeBitmap(Bitmap bitmap, int paperWidth) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        // Log.i(TAG, "ขนาดต้นฉบับของ bitmap: " + width + " x " + height);

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid bitmap dimensions: " + width + "x" + height);
        }

        // ย่อถ้ากว้างเกิน
        if (width > paperWidth) {
            float ratio = (float) height / (float) width;
            width = paperWidth;
            height = Math.max(1, (int) (paperWidth * ratio));
        }

        // ให้ width เป็นพหุคูณของ 8
        width = Math.max(8, (width / 8) * 8);

        // ตรวจอีกรอบ
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid scaled dimensions: " + width + "x" + height);
        }

        // สร้างภาพที่ถูกย่อ
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);

        // ถ้ายังเล็กกว่ากระดาษ → จัดกลาง
        if (width < paperWidth) {
            // Log.i(TAG, "จัด bitmap ให้อยู่กลางกระดาษ ขนาดกระดาษ: " + paperWidth);

            Bitmap centered = Bitmap.createBitmap(paperWidth, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(centered);

            canvas.drawColor(Color.WHITE); // พื้นหลังสีขาว

            int left = (paperWidth - width) / 2;
            canvas.drawBitmap(scaled, left, 0, null);

            if (scaled != bitmap) {
                scaled.recycle(); // cleanup ถ้าไม่ใช่ bitmap เดิม
            }

            return centered;
        } else {
            return scaled;
        }
    }

    private boolean sendBitmapAsEscpos(String ip, Bitmap bitmap, CallbackContext callbackContext) {
        final int MAX_RETRIES = 5;
        final int RETRY_DELAY_MS = 500;

        // Resize + dither bitmap เพื่อความมั่นใจ
        bitmap = resizeBitmap(bitmap, 576);
        bitmap = toBlackAndWhiteDither(bitmap);

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        // Log.i(TAG, String.format("📄 Bitmap size: %d x %d px", width, height));

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Socket socket = null;
            OutputStream out = null;

            try {
                // Log.i(TAG, String.format("🔗 Attempt %d: Connecting to %s:9100", attempt, ip));

                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, 9100), 5000);
                out = socket.getOutputStream();

                // พิมพ์ภาพเป็น ESC/POS
                printBitmapAsRaster(bitmap, out);

                // คำสั่งตัดกระดาษ + ฟีดกระดาษ
                cutPaper(out);

                out.flush();

                // Log.i(TAG, String.format("✅ Printed successfully to %s on attempt %d", ip, attempt));
                callbackContext.success("✅ Printed successfully (ESCPOS)");
                return true;

            } catch (Exception e) {
                Log.e(TAG, String.format("⚠️ Attempt %d failed: %s", attempt, e.getMessage()), e);

                if (attempt == MAX_RETRIES) {
                    callbackContext.error("sendBitmapAsEscpos error after retries: " + e.getMessage());
                    return false;
                }

                // รอก่อน retry
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ignored) {
                }
            } finally {
                // ปิด resources ให้แน่นอน
                try {
                    if (out != null)
                        out.close();
                } catch (Exception ignored) {
                }

                try {
                    if (socket != null)
                        socket.close();
                } catch (Exception ignored) {
                }
            }
        }

        callbackContext.error("sendBitmapAsEscpos failed: unknown error");
        return false;
    }

    public void printBitmapAsRaster(Bitmap bitmap, OutputStream out) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // ต้องเป็นพหุคูณของ 8 เพื่อแบ่งเป็น byte พอดี
        if (width % 8 != 0) {
            throw new IllegalArgumentException("Bitmap width must be a multiple of 8");
        }

        int widthBytes = width / 8; // จำนวน byte ต่อแถว

        byte[] imageBytes = new byte[widthBytes * height];
        int index = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x += 8) {
                byte b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int px = bitmap.getPixel(x + bit, y);
                    int gray = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3;
                    b <<= 1;
                    if (gray < 128) // เลือก threshold ตามความเหมาะสม
                        b |= 1;
                }
                imageBytes[index++] = b;
            }
        }

        // ส่งคำสั่งพิมพ์ภาพแบบ raster — GS v m (m=0..3 เท่านั้น ไม่ใช่ ASCII '0' = 0x30)
        out.write(0x1D); // GS
        out.write(0x76); // 'v'
        out.write(0); // m = 0 normal raster bit image

        out.write(widthBytes & 0xFF); // กว้าง low byte
        out.write((widthBytes >> 8) & 0xFF); // กว้าง high byte
        out.write(height & 0xFF); // สูง low byte
        out.write((height >> 8) & 0xFF); // สูง high byte

        out.write(imageBytes); // ส่งข้อมูลภาพจริง
        out.flush();
    }

    // =================================================================
    // ============================ USB ================================
    // =================================================================
    //
    // เครื่อง POS ฝั่ง Android (Sunmi / Landi / iMin / ฯลฯ) ที่มี USB host
    // สามารถต่อเครื่องพิมพ์ความร้อน Xprinter หรือ Epson ผ่านสาย USB ได้
    // โดยฝั่ง Xprinter ใช้ POSConnect.DEVICE_TYPE_USB และฝั่ง Epson ใช้
    // ePOS2 Printer + target "USB:<deviceName>" (target ที่ Discovery รายงานมา)
    //
    // ค่า brand ที่รองรับ:
    //   - "xprinter"  → Xprinter SDK
    //   - "epson"     → Epson ePOS2 SDK
    //   - "auto"/"all"→ พยายาม Xprinter ก่อน ไม่ได้ค่อย Epson (เฉพาะ list/print)

    private static final String ACTION_USB_PERMISSION =
            "com.yourcompany.pluginwifiprinter.USB_PERMISSION";

    /**
     * คืนรายการเครื่องพิมพ์ USB ที่เห็นอยู่ตอนนี้ (รวมทั้งจาก Xprinter SDK และ Epson Discovery)
     * โครงสร้างผลลัพธ์:
     *   {
     *     "printers": [
     *       {"brand":"xprinter","target":"/dev/bus/usb/001/003","vendorId":1305,"productId":7000,"deviceName":"...","productName":"..."},
     *       {"brand":"epson","target":"USB:000000000000000000","deviceName":"TM-T82","vendorId":1208,"productId":...}
     *     ]
     *   }
     */
    private void listUsbPrinters(String brand, CallbackContext cb) {
        final String wanted = brand == null ? "all" : brand.toLowerCase(Locale.ROOT);
        cordova.getThreadPool().execute(() -> {
            JSONArray printers = new JSONArray();
            try {
                Context ctx = cordova.getActivity().getApplicationContext();

                // --- Epson ก่อน (ลดโอกาส POSConnect จับ USB ก่อน ePOS2) ---
                if ("epson".equals(wanted) || "all".equals(wanted) || "auto".equals(wanted)) {
                    try {
                        List<JSONObject> found = epsonDiscoverUsb(ctx);
                        for (JSONObject o : found) {
                            printers.put(o);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Epson listUsb failed: " + t.getMessage());
                    }
                }

                if ("xprinter".equals(wanted) || "all".equals(wanted) || "auto".equals(wanted)) {
                    try {
                        ensurePosSdkInit();
                        List<UsbDevice> xpDevices = POSConnect.getUsbDevice(ctx);
                        if (xpDevices != null) {
                            for (UsbDevice d : xpDevices) {
                                JSONObject o = new JSONObject();
                                o.put("brand", "xprinter");
                                String target = d.getDeviceName();
                                o.put("target", target);
                                o.put("deviceName", target);
                                o.put("vendorId", d.getVendorId());
                                o.put("productId", d.getProductId());
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    o.put("productName", safeStr(d.getProductName()));
                                    o.put("manufacturerName", safeStr(d.getManufacturerName()));
                                    o.put("serialNumber", safeStrSerial(d, ctx));
                                }
                                printers.put(o);
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Xprinter listUsb failed: " + t.getMessage());
                    }
                }

                JSONObject result = new JSONObject();
                result.put("printers", printers);
                cb.success(result);
            } catch (Exception e) {
                Log.e(TAG, "listUsbPrinters error: " + e.getMessage(), e);
                cb.error("listUsbPrinters error: " + e.getMessage());
            }
        });
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static String safeStrSerial(UsbDevice d, Context ctx) {
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um != null && um.hasPermission(d) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return safeStr(d.getSerialNumber());
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** หน่วงเวลาแบบไม่ throw — หลัง sendData USB ให้เฟิร์มแวร์ระบายก่อน disconnect */
    private static void sleepQuiet(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int usbAttachedDeviceCount(Context ctx) {
        if (ctx == null) return 0;
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) return 0;
        return um.getDeviceList().size();
    }

    /**
     * แปลง target ที่ผู้ใช้/Epson ส่งมาให้ตรงกับ {@link UsbDevice#getDeviceName()} สำหรับค้นหาและขอสิทธิ์
     * เช่น {@code USB:/dev/bus/usb/001/006} → {@code /dev/bus/usb/001/006}
     */
    private static String androidUsbPathForPermissionLookup(String target) {
        if (target == null) return null;
        String t = target.trim();
        if (t.regionMatches(true, 0, "USB:", 0, 4)) {
            String rest = t.length() > 4 ? t.substring(4).trim() : "";
            if (rest.startsWith("/dev/")) {
                return rest;
            }
            return null;
        }
        if (t.startsWith("/dev/bus/usb/") || t.startsWith("/dev/usb/")) {
            return t;
        }
        return null;
    }

    /**
     * ePOS2 ใช้ target จาก Discovery เช่น "USB:xxxxxxxx" หรือ "USB:" เมื่อมีเครื่อง USB Epson เดียว
     * รูปแบบ "USB:/dev/bus/usb/..." ไม่ถูกต้อง
     */
    private static String normalizeEpsonUsbConnectTarget(String target) {
        if (target == null) return "USB:";
        String t = target.trim();
        if (t.isEmpty()) return "USB:";
        if (t.regionMatches(true, 0, "USB:", 0, 4)) {
            String rest = t.length() > 4 ? t.substring(4).trim() : "";
            if (rest.startsWith("/dev/")) {
                Log.w(TAG, "Epson USB: USB:+Android dev path — using USB:");
                return "USB:";
            }
        }
        if (t.startsWith("/dev/bus/usb/") || t.startsWith("/dev/usb/")) {
            Log.w(TAG, "Epson USB: bare Android dev path — using USB:");
            return "USB:";
        }
        return t;
    }

    /**
     * ปิด Epson USB ปลอดภัย — ห้าม clearCommandBuffer หลัง disconnect (TM-T82II พิมพ์รอบถัดไม่ได้)
     */
    private static void releaseEpsonPrinterUsb(Printer printer) {
        if (printer == null) return;
        try {
            try {
                printer.endTransaction();
            } catch (Exception e) {
                Log.w(TAG, "Epson USB endTransaction(release): " + e.getMessage());
            }
            try {
                printer.clearCommandBuffer();
            } catch (Exception e) {
                Log.w(TAG, "Epson USB clearCommandBuffer(pre-disconnect): " + e.getMessage());
            }
            sleepQuiet(100);
            try {
                printer.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Epson USB disconnect: " + String.valueOf(e.getMessage()), e);
            }
            sleepQuiet(650);
        } catch (Throwable t) {
            Log.w(TAG, "Epson USB release cleanup: " + t.getMessage());
        }
    }

    /** connect ล้มเหลว — ไม่เรียก endTransaction/clear (ลด ERR_ILLEGAL รอบถัดไป + ไม่ spam disconnect) */
    private static void abandonEpsonPrinterUsb(Printer printer) {
        if (printer == null) return;
        try {
            printer.disconnect();
        } catch (Throwable ignored) {}
    }

    /**
     * ล้าง persistent Epson USB printer — disconnect + ล้าง static fields
     * เรียกเมื่อเกิด error ระหว่าง print หรือต้องการ reset การเชื่อมต่อ
     */
    private static void invalidateEpsonUsbPrinter() {
        Printer p = sEpsonUsbPrinterInstance;
        sEpsonUsbPrinterInstance = null;
        sEpsonUsbPrinterTarget   = null;
        sEpsonUsbPrinterSeries   = -1;
        sLastGoodEpsonUsbConnectTarget = null;
        if (p != null) {
            try { p.clearCommandBuffer(); } catch (Throwable ignored) {}
            try { p.disconnect();         } catch (Throwable ignored) {}
            sleepQuiet(400);
        }
    }

    /**
     * คืน Epson USB Printer ที่ยังเชื่อมต่ออยู่ หรือสร้างใหม่ถ้าจำเป็น
     * ไม่ disconnect ระหว่าง job เพื่อแก้ปัญหา TM-T82II ERR_CONNECT ครั้งที่ 2+
     */
    private static Printer getOrCreateEpsonUsbPrinter(int series, Context ctx, String target)
            throws Epos2Exception {
        // normalize: "USB:/dev/..." → "USB:"  (dev-path ไม่ใช่ Epson target จริง)
        String tgtNorm = (target == null || target.trim().isEmpty()) ? "USB:" : target.trim();
        if (tgtNorm.regionMatches(true, 0, "USB:", 0, 4)) {
            String rest = tgtNorm.length() > 4 ? tgtNorm.substring(4).trim() : "";
            if (rest.startsWith("/dev/")) tgtNorm = "USB:";
        } else if (tgtNorm.startsWith("/dev/")) {
            tgtNorm = "USB:";
        }
        // มี persistent printer ที่ตรงกับ target+series → reuse โดยไม่ต้อง connect ใหม่
        if (sEpsonUsbPrinterInstance != null
                && sEpsonUsbPrinterSeries == series
                && tgtNorm.equalsIgnoreCase(
                        sEpsonUsbPrinterTarget == null ? "" : sEpsonUsbPrinterTarget)) {
            Log.i(TAG, "Epson USB: reusing persistent connection to \""
                    + sEpsonUsbPrinterTarget + "\"");
            return sEpsonUsbPrinterInstance;
        }
        // target/series เปลี่ยน → disconnect เก่าก่อนแล้วค่อย connect ใหม่
        if (sEpsonUsbPrinterInstance != null) {
            Log.i(TAG, "Epson USB: target/series changed — releasing old connection");
            try { sEpsonUsbPrinterInstance.disconnect(); } catch (Throwable ignored) {}
            sEpsonUsbPrinterInstance = null;
            sEpsonUsbPrinterTarget   = null;
            sEpsonUsbPrinterSeries   = -1;
            sLastGoodEpsonUsbConnectTarget = null;
            sleepQuiet(600);
        }
        // สร้างและ connect ใหม่
        Log.i(TAG, "Epson USB: creating new persistent connection to \"" + tgtNorm + "\"");
        Printer printer = epsonUsbCreateAndConnect(series, ctx, tgtNorm);
        sEpsonUsbPrinterInstance = printer;
        sEpsonUsbPrinterTarget   = tgtNorm;
        sEpsonUsbPrinterSeries   = series;
        return printer;
    }

    /**
     * Discovery — รวบรวม target แล้วเลือกรูปแบบที่ไม่ใช่ Android {@code /dev/...} ก่อน
     * (ถ้าใช้ dev-path แรกสุดแล้ว ERR_CONNECT แต่พอสลับไป USB: discovery คืน path เดิม จะวนซ้ำไม่จบ)
     */
    private static String epsonQuickUsbDiscoveryTarget(Context ctx, long maxWaitMs) {
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch done = new CountDownLatch(1);
        try {
            FilterOption opt = new FilterOption();
            opt.setPortType(Discovery.PORTTYPE_USB);
            opt.setDeviceType(Discovery.TYPE_PRINTER);
            DiscoveryListener listener = new DiscoveryListener() {
                @Override
                public void onDiscovery(DeviceInfo info) {
                    try {
                        String raw = safeStr(info.getTarget()).trim();
                        if (raw.isEmpty()) return;
                        if (!found.contains(raw)) {
                            found.add(raw);
                        }
                        if (!raw.contains("/dev/")) {
                            done.countDown();
                        }
                    } catch (Throwable ignored) {}
                }
            };
            Discovery.start(ctx, opt, listener);
            done.await(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "epsonQuickUsbDiscoveryTarget: " + t.getMessage());
        } finally {
            try {
                Discovery.stop();
            } catch (Throwable ignored) {}
        }
        String[] snap;
        synchronized (found) {
            snap = found.toArray(new String[0]);
        }
        if (snap.length == 0) return null;
        for (String t : snap) {
            if (t != null && !t.contains("/dev/")) {
                return t;
            }
        }
        return snap[0];
    }

    private static Printer epsonUsbCreateAndConnect(int series, Context ctx, String tgtRaw) throws Epos2Exception {
        final boolean multiUsb = usbAttachedDeviceCount(ctx) > 1;
        if (multiUsb) {
            Log.i(TAG, "Epson USB: multiple USB devices attached — will not rely on plain \"USB:\" alone");
        }

        String trimmedRaw = tgtRaw == null ? "" : tgtRaw.trim();
        String tgt = normalizeEpsonUsbConnectTarget(tgtRaw);
        if (!trimmedRaw.equals(tgt)) {
            Log.i(TAG, "Epson USB connect: normalized \"" + tgtRaw + "\" -> \"" + tgt + "\"");
        }
        // แอปอาจเก็บ "USB:/dev/bus/usb/..." — ลอง connect ด้วยค่าดิบก่อน (บางอุปกรณ์/USB: อย่างเดียวแล้ว ERR_CONNECT)
        if ("USB:".equals(tgt) && trimmedRaw.length() > 4
                && trimmedRaw.regionMatches(true, 0, "USB:", 0, 4)) {
            String rest = trimmedRaw.substring(4).trim();
            if (rest.startsWith("/dev/")) {
                tgt = trimmedRaw;
                Log.i(TAG, "Epson USB connect: trying raw dev-path target \"" + tgt + "\"");
            }
        }
        if ("USB:".equals(tgt)) {
            String cached = sLastGoodEpsonUsbConnectTarget;
            if (cached != null && !cached.trim().isEmpty() && !cached.contains("/dev/")) {
                tgt = cached.trim();
                Log.i(TAG, "Epson USB using last successful target \"" + tgt + "\"");
            } else {
                String disc = epsonQuickUsbDiscoveryTarget(ctx, 500);
                if (disc != null && !disc.isEmpty()) {
                    if (!disc.contains("/dev/")) {
                        tgt = disc;
                        Log.i(TAG, "Epson USB pre-connect discovery -> \"" + tgt + "\"");
                    } else if (multiUsb) {
                        tgt = disc;
                        Log.i(TAG, "Epson USB pre-connect: multi-USB — use discovery dev-path \"" + tgt + "\"");
                    } else {
                        Log.i(TAG, "Epson USB pre-connect: discovery only returned dev-path — keep plain USB:");
                    }
                }
            }
        }
        if (multiUsb && "USB:".equals(tgt)) {
            String discForce = epsonQuickUsbDiscoveryTarget(ctx, 1600);
            if (discForce != null && !discForce.isEmpty()) {
                tgt = discForce;
                Log.w(TAG, "Epson USB multi-USB: refuse ambiguous USB: — using discovery \"" + tgt + "\"");
            }
        }
        Epos2Exception last = null;
        Printer printer = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (printer != null) {
                abandonEpsonPrinterUsb(printer);
                printer = null;
            }
            if (attempt > 0) {
                sleepQuiet(200L * attempt);
            }
            printer = new Printer(series, Printer.MODEL_ANK, ctx);
            try {
                Log.i(TAG, "Epson USB Printer.connect attempt=" + (attempt + 1)
                        + " target=\"" + tgt + "\" series=" + series);
                printer.connect(tgt, Printer.PARAM_DEFAULT);
                if (tgt != null && !tgt.trim().isEmpty()) {
                    String t = tgt.trim();
                    // ไม่แคช path แบบ /dev/ — ใช้ซ้ำแล้วมักได้ ERR_ILLEGAL หลังพิมพ์หลายครั้ง
                    if (!t.contains("/dev/")) {
                        sLastGoodEpsonUsbConnectTarget = t;
                    }
                }
                return printer;
            } catch (Epos2Exception e) {
                last = e;
                int st = e.getErrorStatus();
                if (attempt < 3) {
                    if (st == Epos2Exception.ERR_CONNECT) {
                        boolean leftDevPath = false;
                        if (tgt != null && tgt.contains("/dev/")) {
                            Log.w(TAG, "Epson USB ERR_CONNECT with dev-path target; switch to plain USB: (no dev re-apply)");
                            tgt = "USB:";
                            sLastGoodEpsonUsbConnectTarget = null;
                            leftDevPath = true;
                        }
                        String disc = epsonQuickUsbDiscoveryTarget(ctx, 800);
                        if (disc != null && !disc.isEmpty()) {
                            if (disc.contains("/dev/") && leftDevPath) {
                                if (multiUsb) {
                                    tgt = disc;
                                    Log.w(TAG, "Epson USB ERR_CONNECT: multi-USB — retry discovery dev-path \"" + tgt + "\"");
                                } else {
                                    tgt = "USB:";
                                    Log.w(TAG, "Epson USB ERR_CONNECT: discovery still dev-path — retry with USB:");
                                }
                            } else {
                                tgt = disc;
                                Log.w(TAG, "Epson USB ERR_CONNECT; retry with discovery \"" + tgt + "\"");
                            }
                        } else {
                            sleepQuiet(400);
                        }
                    } else if (st == Epos2Exception.ERR_IN_USE) {
                        sleepQuiet(600);
                    } else if (st == Epos2Exception.ERR_ILLEGAL) {
                        Log.w(TAG, "Epson USB ERR_ILLEGAL — invalidate cache / refresh via discovery");
                        sLastGoodEpsonUsbConnectTarget = null;
                        sleepQuiet(500);
                        String disc = epsonQuickUsbDiscoveryTarget(ctx, 1200);
                        if (disc != null && !disc.isEmpty()) {
                            tgt = disc;
                            if (tgt.contains("/dev/") && !multiUsb) {
                                tgt = "USB:";
                                Log.w(TAG, "Epson USB ERR_ILLEGAL fallback to USB: after dev-path from discovery");
                            }
                        } else {
                            if (multiUsb && trimmedRaw.regionMatches(true, 0, "USB:", 0, 4)
                                    && trimmedRaw.contains("/dev/")) {
                                tgt = trimmedRaw;
                                Log.w(TAG, "Epson USB ERR_ILLEGAL: no discovery — retry saved target \"" + tgt + "\"");
                            } else {
                                tgt = "USB:";
                            }
                        }
                    }
                }
                Log.w(TAG, "Epson USB connect attempt " + (attempt + 1) + " failed code=" + st);
            }
        }
        if (last != null) {
            int ec = last.getErrorStatus();
            if (ec == Epos2Exception.ERR_CONNECT || ec == Epos2Exception.ERR_ILLEGAL) {
                sLastGoodEpsonUsbConnectTarget = null;
            }
            abandonEpsonPrinterUsb(printer);
            throw last;
        }
        throw new IllegalStateException("Epson USB connect failed");
    }

    private static String epsonUsbHintForCode(int code, Context ctx) {
        if (code == Epos2Exception.ERR_CONNECT) {
            return "เชื่อมต่อ USB ไม่สำเร็จ — ถอดสายแล้วเสียบใหม่ กดสแกน USB แล้วเลือกเครื่องอีกครั้ง";
        }
        if (code == Epos2Exception.ERR_ILLEGAL) {
            if (ctx != null && usbAttachedDeviceCount(ctx) > 1) {
                return "ต่อ USB หลายเครื่อง — ไม่ใช้ \"USB:\" เปล่าได้ ให้สแกนแล้วเลือก Epson จากรายการหรือถอดเครื่องที่ไม่พิมพ์ชั่วคราว";
            }
            return "พารามิเตอร์ connect ไม่ถูกต้องหรือสถานะ SDK ค้าง — กดสแกน USB ใหม่หรือรีสตาร์ทแอป";
        }
        if (code == Epos2Exception.ERR_IN_USE) {
            return "อุปกรณ์ USB กำลังถูกใช้งาน — รอแล้วลองใหม่";
        }
        if (code == Epos2Exception.ERR_TIMEOUT) {
            return "หมดเวลาเชื่อมต่อ USB";
        }
        return "ดูรหัสใน Epos2Exception";
    }

    /** ค้นหา Epson USB devices ผ่าน Epson Discovery API */
    private List<JSONObject> epsonDiscoverUsb(Context ctx) {
        final List<JSONObject> found = new ArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            FilterOption opt = new FilterOption();
            opt.setPortType(Discovery.PORTTYPE_USB);
            opt.setDeviceType(Discovery.TYPE_PRINTER);

            DiscoveryListener listener = new DiscoveryListener() {
                @Override
                public void onDiscovery(DeviceInfo info) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("brand", "epson");
                        String rawTgt = safeStr(info.getTarget()).trim();
                        o.put("target", rawTgt.isEmpty() ? "USB:" : rawTgt);
                        o.put("deviceName", safeStr(info.getDeviceName()));
                        o.put("ipAddress", safeStr(info.getIpAddress()));
                        o.put("macAddress", safeStr(info.getMacAddress()));
                        synchronized (found) {
                            found.add(o);
                        }
                    } catch (JSONException ignored) {}
                }
            };

            Discovery.start(ctx, opt, listener);
            // Epson Discovery เป็น async — รอช่วงสั้น ๆ ให้ผลลัพธ์ทยอยกลับมา
            try { done.await(2500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            try { Discovery.stop(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "Epson Discovery error: " + t.getMessage());
        }
        synchronized (found) {
            return new ArrayList<>(found);
        }
    }

    /**
     * ขอสิทธิ์เข้าถึงอุปกรณ์ USB จากผู้ใช้ (ถ้ายังไม่เคยอนุมัติ)
     * target = devicePath เช่น "/dev/bus/usb/001/003"
     * คืน success("granted") | success("already") | error(<msg>)
     */
    private void requestUsbPermission(String target, CallbackContext cb) {
        if (target == null || target.trim().isEmpty()) {
            cb.error("ไม่มี target USB");
            return;
        }
        Activity activity = cordova.getActivity();
        if (activity == null || activity.isFinishing()) {
            cb.error("Activity ไม่พร้อม — ลองอีกครั้ง");
            return;
        }
        Context appCtx = activity.getApplicationContext();
        UsbManager um = (UsbManager) appCtx.getSystemService(Context.USB_SERVICE);
        if (um == null) {
            cb.error("UsbManager unavailable");
            return;
        }

        UsbDevice device = null;
        String pathFromEpson = androidUsbPathForPermissionLookup(target);
        if (pathFromEpson != null) {
            device = findUsbDeviceByDeviceName(um, pathFromEpson);
        }
        if (device == null) {
            for (UsbDevice d : um.getDeviceList().values()) {
                if (target.equals(d.getDeviceName())) {
                    device = d;
                    break;
                }
            }
        }
        // ถ้าเป็น target ของ Epson เช่น "USB:..." → หาจาก vendor list ของ Epson แทน
        if (device == null && target.startsWith("USB:")) {
            for (UsbDevice d : um.getDeviceList().values()) {
                // Epson VID = 0x04B8 (1208)
                if (d.getVendorId() == 0x04B8) {
                    device = d;
                    break;
                }
            }
        }

        if (device == null) {
            cb.error("ไม่พบอุปกรณ์ USB: " + target);
            return;
        }

        if (um.hasPermission(device)) {
            cb.success("already");
            return;
        }

        final UsbDevice finalDevice = device;
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                try {
                    c.unregisterReceiver(this);
                } catch (Exception ignored) {}
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    cb.success("granted");
                } else {
                    cb.error("ผู้ใช้ปฏิเสธสิทธิ์ USB");
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        // ต้องใช้ Activity context — บน Sunmi / OEM หลายรุ่น ถ้า register ด้วย ApplicationContext
        // จะไม่ได้รับ broadcast / dialog USB ทำงานผิดปกติ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(receiver, filter);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            piFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(
                activity, 0, new Intent(ACTION_USB_PERMISSION).setPackage(activity.getPackageName()),
                piFlags);
        um.requestPermission(finalDevice, pi);
    }

    /** หา UsbDevice จาก path มาตรฐานของ Android เช่น /dev/bus/usb/001/007 */
    private static UsbDevice findUsbDeviceByDeviceName(UsbManager um, String target) {
        if (um == null || target == null || target.trim().isEmpty()) {
            return null;
        }
        for (UsbDevice d : um.getDeviceList().values()) {
            if (target.equals(d.getDeviceName())) {
                return d;
            }
        }
        return null;
    }

    private static boolean hasUsbPermissionForTarget(Context ctx, String target) {
        if (ctx == null || target == null || target.trim().isEmpty()) {
            return false;
        }
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) {
            return false;
        }
        String t = target.trim();
        String pathFromEpson = androidUsbPathForPermissionLookup(t);
        UsbDevice d = pathFromEpson != null
                ? findUsbDeviceByDeviceName(um, pathFromEpson)
                : findUsbDeviceByDeviceName(um, t);
        if (d == null && t.regionMatches(true, 0, "USB:", 0, 4)) {
            for (UsbDevice x : um.getDeviceList().values()) {
                if (x.getVendorId() == 0x04B8) {
                    d = x;
                    break;
                }
            }
        }
        return d != null && um.hasPermission(d);
    }

    /**
     * พิมพ์ภาพ Base64 ผ่าน USB
     * brand = "xprinter" หรือ "epson"
     * target = devicePath ของ Xprinter / "USB:..." ของ Epson
     * modelStr = (เฉพาะ Epson) "TM_T82"|"TM_T88"|"TM_M30"... ถ้าไม่ใส่ใช้ TM_T82 เป็นค่าตั้งต้น
     */
    private void printBase64ImageToUsb(String brand, String target, String base64Image,
                                       int paperWidth, String modelStr, CallbackContext cb) {
        if (base64Image == null || base64Image.trim().isEmpty()) {
            cb.error("❌ ข้อมูล Base64 ว่างเปล่า");
            return;
        }
        if (target == null || target.trim().isEmpty()) {
            cb.error("❌ ไม่มี USB target");
            return;
        }
        final int widthPx = paperWidth > 0 ? paperWidth : 576;
        final String b = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
        final String lockKey = "usb:" + brand + ":" + target;

        cordova.getThreadPool().execute(() -> {
            if ("epson".equals(b)) {
                Bitmap originalBitmap = null;
                Bitmap processedBitmap = null;
                try {
                    byte[] decoded = Base64.decode(base64Image.replaceAll("\\s", ""), Base64.NO_WRAP);
                    originalBitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                    if (originalBitmap == null) {
                        cb.error("❌ ไม่สามารถแปลง Base64 เป็นรูปภาพได้");
                        return;
                    }
                    processedBitmap = resizeBitmap(originalBitmap, widthPx);
                    processedBitmap = toBlackAndWhiteDither(processedBitmap);
                    synchronized (EPSON_USB_GLOBAL_LOCK) {
                        printBitmapEpsonUsb(target, processedBitmap, widthPx, modelStr, cb);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ USB Print error: " + e.getMessage(), e);
                    cb.error("❌ ข้อผิดพลาด: " + e.getMessage());
                } finally {
                    if (processedBitmap != null && !processedBitmap.isRecycled()) {
                        try { processedBitmap.recycle(); } catch (Exception ignored) {}
                    }
                    if (originalBitmap != null && !originalBitmap.isRecycled()) {
                        try { originalBitmap.recycle(); } catch (Exception ignored) {}
                    }
                }
                return;
            }
            synchronized (printerLocks.computeIfAbsent(lockKey, k -> new Object())) {
                Bitmap originalBitmap = null;
                Bitmap processedBitmap = null;
                try {
                    byte[] decoded = Base64.decode(base64Image.replaceAll("\\s", ""), Base64.NO_WRAP);
                    originalBitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                    if (originalBitmap == null) {
                        cb.error("❌ ไม่สามารถแปลง Base64 เป็นรูปภาพได้");
                        return;
                    }
                    processedBitmap = resizeBitmap(originalBitmap, widthPx);
                    processedBitmap = toBlackAndWhiteDither(processedBitmap);
                    printBitmapXprinterUsb(target, processedBitmap, widthPx, cb);
                } catch (Exception e) {
                    Log.e(TAG, "❌ USB Print error: " + e.getMessage(), e);
                    cb.error("❌ ข้อผิดพลาด: " + e.getMessage());
                } finally {
                    if (processedBitmap != null && !processedBitmap.isRecycled()) {
                        try { processedBitmap.recycle(); } catch (Exception ignored) {}
                    }
                    if (originalBitmap != null && !originalBitmap.isRecycled()) {
                        try { originalBitmap.recycle(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    /** ส่งภาพไปยังเครื่อง Xprinter ผ่าน USB ด้วย POSPrinter (ESC/POS raster) */
    private void printBitmapXprinterUsb(String target, Bitmap bmp, int widthPx, CallbackContext cb) {
        ensurePosSdkInit();
        IDeviceConnection conn = null;
        try {
            Context ctx = cordova.getActivity().getApplicationContext();
            if (!hasUsbPermissionForTarget(ctx, target)) {
                Log.e(TAG, "Xprinter USB: no permission for " + target);
                cb.error("❌ ยังไม่ได้รับสิทธิ์ USB — กดเลือกเครื่องพิมพ์ในรายการอีกครั้ง แล้วกด \"อนุญาต\" เมื่อระบบถาม "
                        + "(หรือไปที่ การตั้งค่า → แอป → สิทธิ์ USB)");
                return;
            }
            conn = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB);
            if (!conn.connectSync(target, EMPTY_POS_LISTENER)) {
                cb.error("❌ เชื่อมต่อ USB เครื่องพิมพ์ไม่สำเร็จ (Xprinter) — ตรวจสาย/พอร์ต หรือถอดเสียบใหม่");
                return;
            }
            POSPrinter printer = new POSPrinter(conn);
            printer.initializePrinter()
                    .printBitmap(bmp, POSConst.ALIGNMENT_CENTER, widthPx)
                    .feedLine(3)
                    .cutHalfAndFeed(1);

            // ให้ SDK ระบายข้อมูลออกก่อนปิด socket (USB เร็วกว่า LAN — 200ms พอ)
            try { Thread.sleep(300); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try { conn.closeSync(); } catch (Exception closeEx) {
                Log.w(TAG, "Xprinter USB closeSync warning: " + closeEx.getMessage());
            } finally {
                conn = null;
            }
            cb.success("1");
        } catch (Exception e) {
            Log.e(TAG, "Xprinter USB print error: " + e.getMessage(), e);
            cb.error("Xprinter USB print error: " + e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.closeSync(); } catch (Exception ignored) {}
            }
        }
    }

    /** ส่งภาพไปยังเครื่อง Epson ผ่าน USB ด้วย ePOS2 Printer + addImage */
    private void printBitmapEpsonUsb(String target, Bitmap bmp, int widthPx,
                                     String modelStr, CallbackContext cb) {
        Context appCtx = getAppCtxForEpson();
        if (appCtx == null) {
            cb.error("Epson USB: ไม่พบ Application context");
            return;
        }
        boolean sentOk = false;
        try {
            if (!hasUsbPermissionForTarget(appCtx, target)) {
                cb.error("ยังไม่ได้รับสิทธิ์ USB — กดเลือกเครื่องพิมพ์ในรายการอีกครั้ง แล้วกดอนุญาต");
                return;
            }
            int series = epsonModelFromString(modelStr);
            String tgt = (target == null || target.isEmpty()) ? "USB:" : target;

            // ใช้ persistent printer (ไม่ disconnect ระหว่าง job — แก้ ERR_CONNECT ครั้งที่ 2+)
            Printer printer = getOrCreateEpsonUsbPrinter(series, appCtx, tgt);
            // ล้าง command buffer ก่อนเริ่ม transaction ใหม่ทุกครั้ง
            try { printer.clearCommandBuffer(); } catch (Exception ignored) {}

            // beginTransaction — ถ้าล้มด้วย ERR_ILLEGAL/ERR_CONNECT ให้ reset แล้วลองใหม่ 1 ครั้ง
            try {
                printer.beginTransaction();
            } catch (Epos2Exception ex) {
                int st = ex.getErrorStatus();
                if (st == Epos2Exception.ERR_ILLEGAL || st == Epos2Exception.ERR_CONNECT) {
                    Log.w(TAG, "Epson USB beginTransaction(" + st + ") — reset persistent printer and retry");
                    invalidateEpsonUsbPrinter();
                    sleepQuiet(700);
                    printer = getOrCreateEpsonUsbPrinter(series, appCtx, tgt);
                    try { printer.clearCommandBuffer(); } catch (Exception ignored) {}
                    printer.beginTransaction();
                } else {
                    throw ex;
                }
            }

            printer.addTextAlign(Printer.ALIGN_CENTER);
            printer.addImage(
                    bmp,
                    0, 0,
                    bmp.getWidth(), bmp.getHeight(),
                    Printer.COLOR_1,
                    Printer.MODE_MONO,
                    Printer.HALFTONE_THRESHOLD, // bitmap ถูก dither แล้ว — THRESHOLD เร็วกว่า
                    Printer.PARAM_DEFAULT,
                    Printer.COMPRESS_AUTO);
            printer.addFeedLine(3);
            printer.addCut(Printer.CUT_FEED);

            printer.sendData(Printer.PARAM_DEFAULT);
            // sendData บล็อกจนข้อมูลถึงเครื่องพิมพ์แล้ว — 50ms พอสำหรับ margin เล็กน้อย
            sleepQuiet(50);
            try {
                printer.endTransaction();
            } catch (Exception e) {
                Log.w(TAG, "Epson USB endTransaction: " + e.getMessage());
            }
            // *** ไม่ disconnect — เก็บ connection ไว้สำหรับงานพิมพ์ถัดไป ***
            sentOk = true;
        } catch (Epos2Exception e) {
            int code = e.getErrorStatus();
            Log.e(TAG, "Epson USB print error code=" + code, e);
            invalidateEpsonUsbPrinter(); // reset persistent state เมื่อเกิด error
            cb.error("Epson USB print error: code=" + code + " — " + epsonUsbHintForCode(code, appCtx));
        } catch (Exception e) {
            Log.e(TAG, "Epson USB print error: " + e.getMessage(), e);
            invalidateEpsonUsbPrinter();
            cb.error("Epson USB print error: " + e.getMessage());
        }
        if (sentOk) {
            cb.success("1");
        }
    }

    /** เปิดลิ้นชักเก็บเงิน — รองรับทั้ง Xprinter (POSConst.PIN_TWO) และ Epson (addPulse) ผ่าน USB */
    private void openCashDrawerUsb(String brand, String target, String modelStr, CallbackContext cb) {
        if (target == null || target.trim().isEmpty()) {
            cb.error("ไม่มี USB target");
            return;
        }
        final String b = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
        cordova.getThreadPool().execute(() -> {
            if ("epson".equals(b)) {
                openCashDrawerEpsonUsb(target, modelStr, cb);
            } else {
                openCashDrawerXprinterUsb(target, cb);
            }
        });
    }

    /**
     * ส่ง pulse เปิดลิ้นชัก Xprinter — ลองทั้ง PIN 2 และ PIN 5
     * (Xprinter หลายรุ่น/สาย RJ11 ต่อคนละ pin; USB บน Android 7 ต้องรอ flush ก่อนปิด connection)
     */
    private static void pulseXprinterCashDrawer(POSPrinter printer) throws Exception {
        printer.initializePrinter();
        printer.openCashBox(POSConst.PIN_TWO);
        sleepQuiet(80);
        printer.openCashBox(POSConst.PIN_FIVE);
    }

    private void openCashDrawerXprinterUsb(String target, CallbackContext cb) {
        ensurePosSdkInit();
        final String lockKey = "usb:" + target;
        synchronized (printerLocks.computeIfAbsent(lockKey, k -> new Object())) {
            IDeviceConnection conn = null;
            try {
                Context ctx = cordova.getActivity().getApplicationContext();
                if (!hasUsbPermissionForTarget(ctx, target)) {
                    cb.error("ยังไม่ได้รับสิทธิ์ USB — กดเลือกเครื่องพิมพ์ในรายการอีกครั้ง แล้วกดอนุญาต");
                    return;
                }
                conn = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB);
                if (!conn.connectSync(target, EMPTY_POS_LISTENER)) {
                    cb.error("เชื่อมต่อ USB เครื่องพิมพ์ไม่สำเร็จ");
                    return;
                }
                pulseXprinterCashDrawer(new POSPrinter(conn));
                // USB bulk transfer บน Android 7 (Sunmi) ต้องรอให้คำสั่งออกก่อน closeSync — 150ms ไม่พอ
                sleepQuiet(400);
                cb.success("✅ เปิดลิ้นชักเก็บเงินสำเร็จ");
                Log.i(TAG, "✅ openCashDrawerUsb (xprinter) " + target);
            } catch (Exception e) {
                Log.e(TAG, "openCashDrawerUsb (xprinter) error: " + e.getMessage(), e);
                cb.error("เปิดลิ้นชักล้มเหลว: " + e.getMessage());
            } finally {
                if (conn != null) {
                    try { conn.closeSync(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private void openCashDrawerEpsonUsb(String target, String modelStr, CallbackContext cb) {
        Context appCtx = getAppCtxForEpson();
        if (appCtx == null) {
            cb.error("Epson USB: ไม่พบ Application context");
            return;
        }
        synchronized (EPSON_USB_GLOBAL_LOCK) {
            boolean ok = false;
            try {
                if (!hasUsbPermissionForTarget(appCtx, target)) {
                    cb.error("ยังไม่ได้รับสิทธิ์ USB — กดเลือกเครื่องพิมพ์ในรายการอีกครั้ง แล้วกดอนุญาต");
                    return;
                }
                int series = epsonModelFromString(modelStr);
                String tgt = (target == null || target.isEmpty()) ? "USB:" : target;
                Printer printer = getOrCreateEpsonUsbPrinter(series, appCtx, tgt);
                try { printer.clearCommandBuffer(); } catch (Exception ignored) {}
                try {
                    printer.beginTransaction();
                } catch (Epos2Exception ex) {
                    int st = ex.getErrorStatus();
                    if (st == Epos2Exception.ERR_ILLEGAL || st == Epos2Exception.ERR_CONNECT) {
                        Log.w(TAG, "Epson USB drawer beginTransaction(" + st + ") — reset and retry");
                        invalidateEpsonUsbPrinter();
                        sleepQuiet(700);
                        printer = getOrCreateEpsonUsbPrinter(series, appCtx, tgt);
                        try { printer.clearCommandBuffer(); } catch (Exception ignored) {}
                        printer.beginTransaction();
                    } else {
                        throw ex;
                    }
                }
                printer.addPulse(Printer.DRAWER_2PIN, Printer.PULSE_100);
                printer.sendData(Printer.PARAM_DEFAULT);
                sleepQuiet(80);
                try {
                    printer.endTransaction();
                } catch (Exception e) {
                    Log.w(TAG, "Epson drawer endTransaction: " + e.getMessage());
                }
                // *** ไม่ disconnect — เก็บ connection ไว้ ***
                ok = true;
            } catch (Epos2Exception e) {
                int code = e.getErrorStatus();
                Log.e(TAG, "openCashDrawerUsb (epson) code=" + code, e);
                invalidateEpsonUsbPrinter();
                cb.error("Epson open drawer error: code=" + code + " — " + epsonUsbHintForCode(code, appCtx));
            } catch (Exception e) {
                Log.e(TAG, "openCashDrawerUsb (epson) error: " + e.getMessage(), e);
                invalidateEpsonUsbPrinter();
                cb.error("Epson open drawer error: " + e.getMessage());
            }
            if (ok) {
                cb.success("✅ เปิดลิ้นชักเก็บเงินสำเร็จ (Epson)");
            }
        }
    }

    /** เคลียร์คิวเครื่องพิมพ์ — initialize printer ผ่าน USB */
    private void clearPrinterQueueUsb(String brand, String target, String modelStr, CallbackContext cb) {
        if (target == null || target.trim().isEmpty()) {
            cb.error("ไม่มี USB target");
            return;
        }
        final String b = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
        cordova.getThreadPool().execute(() -> {
            if ("epson".equals(b)) {
                Context appCtx = getAppCtxForEpson();
                if (appCtx == null) {
                    cb.error("Epson USB: ไม่พบ Application context");
                    return;
                }
                synchronized (EPSON_USB_GLOBAL_LOCK) {
                    boolean ok = false;
                    try {
                        if (!hasUsbPermissionForTarget(appCtx, target)) {
                            cb.error("ยังไม่ได้รับสิทธิ์ USB — กดเลือกเครื่องพิมพ์ในรายการอีกครั้ง แล้วกดอนุญาต");
                            return;
                        }
                        int series = epsonModelFromString(modelStr);
                        String tgt = (target == null || target.isEmpty()) ? "USB:" : target;
                        Printer printer = getOrCreateEpsonUsbPrinter(series, appCtx, tgt);
                        printer.clearCommandBuffer();
                        sleepQuiet(200);
                        // *** ไม่ disconnect — เก็บ connection ไว้ ***
                        ok = true;
                    } catch (Exception e) {
                        Log.e(TAG, "clearPrinterQueueUsb (epson) error: " + e.getMessage(), e);
                        invalidateEpsonUsbPrinter();
                        cb.error("Epson clear queue error: " + e.getMessage());
                    }
                    if (ok) {
                        cb.success("✅ เคลียร์คิวเครื่องพิมพ์ (Epson USB) แล้ว");
                    }
                }
            } else {
                ensurePosSdkInit();
                IDeviceConnection conn = null;
                try {
                    Context ctx = cordova.getActivity().getApplicationContext();
                    if (!hasUsbPermissionForTarget(ctx, target)) {
                        cb.error("ยังไม่ได้รับสิทธิ์ USB — กดเลือกเครื่องพิมพ์ในรายการอีกครั้ง");
                        return;
                    }
                    conn = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB);
                    if (!conn.connectSync(target, EMPTY_POS_LISTENER)) {
                        cb.error("เชื่อมต่อ USB ไม่สำเร็จ");
                        return;
                    }
                    new POSPrinter(conn).initializePrinter();
                    cb.success("✅ เคลียร์คิวเครื่องพิมพ์ (Xprinter USB) แล้ว");
                } catch (Exception e) {
                    Log.e(TAG, "clearPrinterQueueUsb (xprinter) error: " + e.getMessage(), e);
                    cb.error("Xprinter clear queue error: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        try { conn.closeSync(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    /** map "TM_T82"/"TM_T82X"/"TM-T82" → Printer.TM_T82 (ค่าตั้งต้น) */
    private static int epsonModelFromString(String modelStr) {
        if (modelStr == null) return Printer.TM_T82;
        String s = modelStr.toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "");
        switch (s) {
            case "TM_T82":
            case "TM_T82X":
            case "TM_T82II":
            case "TM_T82III":
                return Printer.TM_T82;
            case "TM_T88":
            case "TM_T88V":
            case "TM_T88VI":
            case "TM_T88VII":
                return Printer.TM_T88;
            case "TM_T20":
            case "TM_T20II":
            case "TM_T20III":
                return Printer.TM_T20;
            case "TM_T70":
            case "TM_T70II":
                return Printer.TM_T70;
            case "TM_T81":
                return Printer.TM_T81;
            case "TM_T83":
            case "TM_T83III":
                return Printer.TM_T83;
            case "TM_M10":
                return Printer.TM_M10;
            case "TM_M30":
                return Printer.TM_M30;
            case "TM_M30II":
                return Printer.TM_M30II;
            case "TM_M30III":
                return Printer.TM_M30III;
            case "TM_M50":
                return Printer.TM_M50;
            case "TM_P20":
                return Printer.TM_P20;
            case "TM_P60":
                return Printer.TM_P60;
            case "TM_P80":
                return Printer.TM_P80;
            default:
                return Printer.TM_T82;
        }
    }
}
