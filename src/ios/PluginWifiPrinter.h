#import <Cordova/CDV.h>
#import <UIKit/UIKit.h>

@interface PluginWifiPrinter : CDVPlugin

- (void)scanIpList:(CDVInvokedUrlCommand*)command;
- (void)printBase64ImageToXprinter:(CDVInvokedUrlCommand*)command;
- (void)clearPrinterQueue:(CDVInvokedUrlCommand*)command;

- (BOOL)sendImageAsRasterChunk:(UIImage*)image socket:(int)sock;

- (void)checkIpWithPort:(CDVInvokedUrlCommand*)command;

@end