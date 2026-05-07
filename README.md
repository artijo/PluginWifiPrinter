## PluginWifiPrinter

Cordova Plugin สำหรับพิมพ์ผ่าน Wi-Fi Printer

### Install
```bash
ionic cordova plugin add https://github.com/PreeDeer/pluginwifiprinter.git

### remove
ionic cordova plugin remove com.yourcompany.pluginwifiprinter

### Usage
cordova.plugins.PluginWifiPrinter.print(
  ip,
  port,
  data,
  success => console.log(success),
  err => console.error(err)
);

## รองรับแพลตฟอร์ม ตอนนี้ Android ตัว ios ยังเป็นเวอร์ชันก์เก่า
- Android 
- iOS
