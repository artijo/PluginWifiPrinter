var exec = require('cordova/exec');

// สแกนเครื่องพิมพ์
exports.scanNetworkDevices = function (success, error) {
  console.log('scanNetworkDevices called');

  exec((res) => {
    let parsed = res;
    if (typeof res === 'string') {
      try {
        parsed = JSON.parse(res);
      } catch (e) {
        console.warn('JSON parse error:', e);
        return success([]); // ส่งกลับ array ว่างถ้า parse ไม่ได้
      }
    }
    success(parsed.printers || []);
  }, (err) => {
    if (error) error(err);
  }, 'PluginWifiPrinter', 'scanNetworkDevices', []);
};

//-เช็คแต่ipที่กรอก
exports.scanIpList = function (ipList, success, error) {
    exec(
        success,
        error,
        'PluginWifiPrinter',   // 📄 ชื่อ plugin
        'scanIpList',            // 📄 action ที่เพิ่มใน Java
        [ipList]               // 📄 ต้องเป็น array of IPs
    );
};

// เช็คเครื่องพิมพ์แบบกำหนด IP:PORT
exports.checkIpWithPort = function (ipWithPort, success, error) {
  if (!ipWithPort) {
    if (error) error('ipWithPort is required');
    return;
  }

  exec(
    (res) => {
      success?.(res);
    },
    (err) => {
      error?.(err);
    },
    'PluginWifiPrinter',     // 📄 plugin name
    'checkIpWithPort',       // 📄 action ใน Java
    [ipWithPort]             // 📄 arguments (array)
  );
};



// พิมพ์ทดสอบเครื่อง Xprinter
exports.printTestXprinter = function (ip, success, error) {
  console.log('printTestXprinter called with IP:', ip);

  exec(success, error, 'PluginWifiPrinter', 'printTestXprinter', [ip]);
};

// พิมพ์ข้อความ base64 ไปยัง Xprinter
// รองรับสองแบบ:
//   (ip, base64, success, error) — เก่า ไม่ส่งความกว้าง → native ใช้ default 576
//   (ip, base64, paperWidth, success, error) — ที่ Angular ใช้ (58mm=384, 80mm=576)
exports.printBase64ImageToXprinter = function (ip, base64Data, arg3, arg4, arg5) {
  var paperWidth = 576;
  var success;
  var errorCb;

  if (typeof arg3 === 'function') {
    success = arg3;
    errorCb = arg4;
  } else {
    var w = arg3 != null && arg3 !== '' ? Number(arg3) : 576;
    paperWidth = isNaN(w) || w <= 0 ? 576 : w;
    success = arg4;
    errorCb = arg5;
  }

  console.log(
    'printBase64ImageToXprinter called IP:',
    ip,
    'paperWidth:',
    paperWidth
  );

  exec(
    success,
    errorCb,
    'PluginWifiPrinter',
    'printBase64ImageToXprinter',
    [ip, base64Data, paperWidth]
  );
};

// ฟังก์ชันส่งข้อความเป็นภาพไปพิมพ์
exports.printTextAsImage = function (ip, text, success, error) {
  console.log('printTextAsImage called with IP:', ip, 'and text:', text);

  exec(success, error, 'PluginWifiPrinter', 'printTextAsImage', [ip, text]);
};

// พิมพ์ HTML เป็นบิล (WebView → ภาพ → พิมพ์)
exports.printHtmlBill = function (ip, html, success, error) {
  console.log('printHtmlBill called with IP:', ip);

  exec(success, error, 'PluginWifiPrinter', 'printHtmlBill', [ip, html]);
};

// ล้างคิวเครื่องพิมพ์ (รีเซ็ตเครื่องพิมพ์)
exports.clearPrinterQueue = function (ip, success, error) {
  console.log('clearPrinterQueue called with IP:', ip);

  exec(success, error, 'PluginWifiPrinter', 'clearPrinterQueue', [ip]);
};


exports.openCashDrawer = function (ip, success, error) {
  console.log('openCashDrawer called with IP:', ip);

  exec(success, error, 'PluginWifiPrinter', 'openCashDrawer', [ip]);
};

// พิมพ์ Bitmap (Base64) ผ่าน SPI bus เช่น /dev/spidev0.0
//   spiDevicePath : path ของ SPI character device (default: '/dev/spidev0.0')
//   base64Data    : ข้อมูลรูปภาพ Base64 (ไม่ต้องมี data:image/...)
//   paperWidth    : ความกว้างกระดาษเป็น px (default 384 = 58mm, ใช้ 576 สำหรับ 80mm)
exports.printBitmapToSpi = function (spiDevicePath, base64Data, paperWidth, success, error) {
  console.log('printBitmapToSpi called with device:', spiDevicePath, 'width:', paperWidth);

  exec(
    success,
    error,
    'PluginWifiPrinter',
    'printBitmapToSpi',
    [spiDevicePath || '/dev/spidev0.0', base64Data, paperWidth || 384]
  );
};

