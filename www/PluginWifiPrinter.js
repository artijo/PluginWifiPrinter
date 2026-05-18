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

//============================================================
//============= USB (Xprinter / Epson)  ======================
//============================================================
// เครื่อง POS Android (Sunmi/Landi/iMin) เสียบสาย USB เข้ากับเครื่องพิมพ์
// บราเดอร์ Xprinter (XP-C300H/XP-T80/ฯลฯ) ใช้ POSConnect.DEVICE_TYPE_USB
// บราเดอร์ Epson (TM-T82X/TM-T88/ฯลฯ) ใช้ Epson ePOS2 SDK
//
// บน iOS รองรับเฉพาะ Epson (USB MFi) — Xprinter iOS USB จะส่ง error กลับ

/**
 * รายการอุปกรณ์ USB ที่เห็นอยู่ตอนนี้
 * @param {string} brand  "xprinter"|"epson"|"all" (default "all")
 * @returns success({ printers: [{brand,target,deviceName,vendorId,productId,...}] })
 */
exports.listUsbPrinters = function (brand, success, error) {
  if (typeof brand === 'function') {
    error = success;
    success = brand;
    brand = 'all';
  }
  exec(
    function (res) {
      var parsed = res;
      if (typeof res === 'string') {
        try { parsed = JSON.parse(res); }
        catch (e) { console.warn('listUsbPrinters parse error', e); parsed = { printers: [] }; }
      }
      success && success(parsed && parsed.printers ? parsed.printers : []);
    },
    function (err) { error && error(err); },
    'PluginWifiPrinter',
    'listUsbPrinters',
    [brand || 'all']
  );
};

/**
 * ขอสิทธิ์เข้าถึงอุปกรณ์ USB (Android เท่านั้น — ครั้งแรกจะเห็น dialog ระบบ)
 * @param {string} target  devicePath เช่น "/dev/bus/usb/001/003" หรือ "USB:..." (Epson)
 * @returns success("granted"|"already") | error(message)
 */
exports.requestUsbPermission = function (target, success, error) {
  exec(success, error, 'PluginWifiPrinter', 'requestUsbPermission', [target]);
};

/**
 * พิมพ์ภาพ Base64 ผ่าน USB
 * @param {string} brand        "xprinter"|"epson"
 * @param {string} target       devicePath / "USB:<serial>"
 * @param {string} base64Data   รูปภาพ Base64 (ไม่มี data:image/png;base64,)
 * @param {number} paperWidth   ความกว้างเป็น px (384=58mm, 576=80mm)
 * @param {string} model        (เฉพาะ Epson) เช่น "TM_T82","TM_T88","TM_M30"
 */
exports.printBase64ImageToUsb = function (brand, target, base64Data, paperWidth, model, success, error) {
  if (typeof paperWidth === 'function') {
    error = model;
    success = paperWidth;
    paperWidth = 576;
    model = '';
  } else if (typeof model === 'function') {
    error = success;
    success = model;
    model = '';
  }
  var w = paperWidth != null && paperWidth !== '' ? Number(paperWidth) : 576;
  if (!isFinite(w) || w <= 0) w = 576;

  exec(
    success,
    error,
    'PluginWifiPrinter',
    'printBase64ImageToUsb',
    [brand || 'xprinter', target, base64Data, w, model || '']
  );
};

/**
 * เปิดลิ้นชักเก็บเงินผ่าน USB
 */
exports.openCashDrawerUsb = function (brand, target, model, success, error) {
  if (typeof model === 'function') {
    error = success;
    success = model;
    model = '';
  }
  exec(
    success,
    error,
    'PluginWifiPrinter',
    'openCashDrawerUsb',
    [brand || 'xprinter', target, model || '']
  );
};

/**
 * เคลียร์คิวเครื่องพิมพ์ผ่าน USB
 */
exports.clearPrinterQueueUsb = function (brand, target, model, success, error) {
  if (typeof model === 'function') {
    error = success;
    success = model;
    model = '';
  }
  exec(
    success,
    error,
    'PluginWifiPrinter',
    'clearPrinterQueueUsb',
    [brand || 'xprinter', target, model || '']
  );
};

