package com.yourcompany.pluginwifiprinter;

import android.content.Context;
import android.graphics.*;
import android.net.wifi.WifiManager;
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

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PluginWifiPrinter extends CordovaPlugin {

    private static final String TAG = "PluginWifiPrinter";
    private CallbackContext scanCallbackContext;

    private StringBuilder base64Buffer = new StringBuilder();

    // ใหม่ 25/2/2026
    private static final Map<String, Object> printerLocks = new ConcurrentHashMap<>();

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

    @Override
    public void pluginInitialize() {
        super.pluginInitialize();
        ensurePosSdkInit();
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

                    new POSPrinter(conn).openCashBox(POSConst.PIN_TWO);
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
        int width = original.getWidth();
        int height = original.getHeight();

        // สร้าง bitmap ใหม่เพื่อเขียนลงไป
        Bitmap bwBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        // แปลงเป็น grayscale array
        int[][] gray = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = original.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                gray[y][x] = (r + g + b) / 3;
            }
        }

        // Floyd–Steinberg Dithering
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int oldPixel = gray[y][x];
                // int newPixel = oldPixel < 128 ? 0 : 255;
                int newPixel = oldPixel < 160 ? 0 : 255;
                int error = oldPixel - newPixel;

                bwBitmap.setPixel(x, y, newPixel == 0 ? Color.BLACK : Color.WHITE);

                // กระจาย error
                if (x + 1 < width) {
                    gray[y][x + 1] += error * 7 / 16;
                }
                if (y + 1 < height) {
                    if (x > 0) {
                        gray[y + 1][x - 1] += error * 3 / 16;
                    }
                    gray[y + 1][x] += error * 5 / 16;
                    if (x + 1 < width) {
                        gray[y + 1][x + 1] += error * 1 / 16;
                    }
                }
            }
        }

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
}