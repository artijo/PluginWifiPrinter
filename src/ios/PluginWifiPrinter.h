#import <Cordova/CDV.h>
#import <UIKit/UIKit.h>

@interface PluginWifiPrinter : CDVPlugin

- (void)scanIpList:(CDVInvokedUrlCommand*)command;
- (void)printBase64ImageToXprinter:(CDVInvokedUrlCommand*)command;
- (void)clearPrinterQueue:(CDVInvokedUrlCommand*)command;

- (BOOL)sendImageAsRasterChunk:(UIImage*)image socket:(int)sock;

- (void)checkIpWithPort:(CDVInvokedUrlCommand*)command;
- (void)openCashDrawer:(CDVInvokedUrlCommand*)command;

// USB — Epson MFi printers on iOS/iPadOS (Xprinter iOS USB is not supported by their SDK)
- (void)requestUsbPermission:(CDVInvokedUrlCommand*)command;
- (void)listUsbPrinters:(CDVInvokedUrlCommand*)command;
- (void)printBase64ImageToUsb:(CDVInvokedUrlCommand*)command;
- (void)openCashDrawerUsb:(CDVInvokedUrlCommand*)command;
- (void)clearPrinterQueueUsb:(CDVInvokedUrlCommand*)command;

@end