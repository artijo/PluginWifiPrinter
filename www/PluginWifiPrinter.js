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
exports.printBase64ImageToXprinter = function (ip, base64Data, success, error) {
  console.log('printBase64ImageToXprinter called with IP:', ip);

  exec(success, error, 'PluginWifiPrinter', 'printBase64ImageToXprinter', [ip, base64Data]);
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


