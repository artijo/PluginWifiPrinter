#import "PluginWifiPrinter.h"
#import "POSPrinterSDK.h"
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>

/** Temporary delegate to await Xprinter LAN connect. */
@interface DeltafoodXprinterWifiGate : NSObject <POSWIFIManagerDelegate>
@property (nonatomic) dispatch_semaphore_t connectSem;
@property (nonatomic, assign) BOOL didSignal;
@property (nonatomic, assign) BOOL didConnect;
@end

@implementation DeltafoodXprinterWifiGate

- (void)POSwifiConnectedToHost:(NSString *)host port:(UInt16)port {
    self.didConnect = YES;
    if (!self.didSignal && self.connectSem) {
        self.didSignal = YES;
        dispatch_semaphore_signal(self.connectSem);
    }
}

- (void)POSwifiDisconnectWithError:(NSError *)error {
    if (!self.didConnect && !self.didSignal && self.connectSem) {
        self.didSignal = YES;
        dispatch_semaphore_signal(self.connectSem);
    }
}

@end

@implementation PluginWifiPrinter

#pragma mark - Public Methods

- (void)scanIpList:(CDVInvokedUrlCommand*)command {
    NSArray *ips = [command.arguments objectAtIndex:0];
    if (ips == nil || ips.count == 0) {
        [self sendError:@"ไม่มี IP ที่ส่งมา" command:command];
        return;
    }

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSMutableArray *printerDevices = [NSMutableArray array];
        for (NSString *ipAndPort in ips) {
            NSString *ip = ipAndPort;
            int port = 9100;

            NSArray *parts = [ipAndPort componentsSeparatedByString:@":"];
            if (parts.count == 2) {
                ip = parts[0];
                port = [parts[1] intValue];
            }

            NSLog(@"Checking printer at %@:%d", ip, port);
            int sock = [self connectToPrinter:ip port:port];
            if (sock >= 0) {
                close(sock);
                [printerDevices addObject:ipAndPort];
            }
        }
        if (printerDevices.count > 0) {
            NSDictionary *result = @{@"printers": printerDevices};
            CDVPluginResult *pluginResult =
                [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:result];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        } else {
            [self sendError:@"ไม่พบเครื่องพิมพ์ใด ๆ" command:command];
        }
    });
}

- (void)printBase64ImageToXprinter:(CDVInvokedUrlCommand*)command {
    NSString *ipAndPort = [command.arguments objectAtIndex:0];
    NSString *base64String = [command.arguments objectAtIndex:1];

    CGFloat paperWidth = 576.f;
    if (command.arguments.count > 2) {
        NSObject *pw = [command.arguments objectAtIndex:2];
        if ([pw respondsToSelector:@selector(floatValue)]) {
            paperWidth = [(NSNumber*)pw floatValue];
        }
        if (paperWidth <= 0) {
            paperWidth = 576.f;
        }
    }

    if (ipAndPort == nil || base64String == nil || [ipAndPort length] == 0 || [base64String length] == 0) {
        [self sendError:@"IP หรือ Base64 ว่าง" command:command];
        return;
    }

    NSString *host = ipAndPort;
    UInt16 port = 9100;
    NSRange colon = [ipAndPort rangeOfString:@":" options:NSBackwardsSearch];
    if (colon.location != NSNotFound && colon.location + 1 < [ipAndPort length]) {
        NSString *maybePort =
            [[ipAndPort substringFromIndex:colon.location + 1]
             stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
        NSCharacterSet *nonDigits = [[NSCharacterSet decimalDigitCharacterSet] invertedSet];
        if ([maybePort length] > 0 && [maybePort rangeOfCharacterFromSet:nonDigits].location == NSNotFound) {
            port = (UInt16)[maybePort intValue];
            host = [[ipAndPort substringToIndex:colon.location]
                    stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
        }
    }

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        POSWIFIManager *wifi = [POSWIFIManager sharedInstance];
        id<POSWIFIManagerDelegate> previousDelegate = wifi.delegate;

        NSData *imageData =
            [[NSData alloc] initWithBase64EncodedString:base64String
                                                options:NSDataBase64DecodingIgnoreUnknownCharacters];
        if (imageData == nil) {
            dispatch_async(dispatch_get_main_queue(), ^{
                [self sendError:@"ไม่สามารถแปลง Base64 เป็น NSData ได้" command:command];
            });
            return;
        }
        UIImage *image = [UIImage imageWithData:imageData];
        if (image == nil) {
            dispatch_async(dispatch_get_main_queue(), ^{
                [self sendError:@"ไม่สามารถแปลง NSData เป็น UIImage ได้" command:command];
            });
            return;
        }

        UIImage *processedImage = [self resizeImage:image maxWidth:paperWidth];
        UIImage *bwImage = [self convertToBlackAndWhite:processedImage];

        DeltafoodXprinterWifiGate *gate = [DeltafoodXprinterWifiGate new];
        gate.connectSem = dispatch_semaphore_create(0);
        wifi.delegate = gate;

        [wifi disconnect];
        [NSThread sleepForTimeInterval:0.06];
        gate.didConnect = NO;
        gate.didSignal = NO;

        dispatch_time_t deadline = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(12.0 * NSEC_PER_SEC));
        NSString *failureReason = @"เชื่อมต่อเครื่องพิมพ์ไม่สำเร็จ";

        BOOL connected = NO;
        [wifi connectWithHost:host port:port];
        if (dispatch_semaphore_wait(gate.connectSem, deadline) == 0 && gate.didConnect) {
            connected = [wifi printerIsConnect] || wifi.isConnect;
        }
        wifi.delegate = previousDelegate;

        if (!connected) {
            [wifi disconnect];
            dispatch_async(dispatch_get_main_queue(), ^{
                [self sendError:failureReason command:command];
            });
            return;
        }

        NSMutableData *dataM = [NSMutableData dataWithData:[POSCommand initializePrinter]];
        [dataM appendData:[POSCommand selectAlignment:POS_ALIGNMENT_CENTER]];
        [dataM appendData:[POSCommand printRasteBmpWithM:RasterNolmorWH
                                                andImage:bwImage
                                                 andType:Dithering]];
        [dataM appendData:[POSCommand printAndFeedForwardWhitN:6]];
        [dataM appendData:[POSCommand selectCutPageModelAndCutpage:1]];

        dispatch_semaphore_t writeSem = dispatch_semaphore_create(0);
        __block BOOL writeOk = NO;
        [wifi writeCommandWithData:dataM
                    writeCallBack:^(BOOL success, NSError *error) {
                        writeOk = success;
                        if (!success && error != nil) {
                            NSLog(@"PluginWifiPrinter Xprinter write error: %@", error);
                        }
                        dispatch_semaphore_signal(writeSem);
                    }];

        dispatch_time_t wDeadline = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(120.0 * NSEC_PER_SEC));
        dispatch_semaphore_wait(writeSem, wDeadline);
        [wifi disconnect];

        dispatch_async(dispatch_get_main_queue(), ^{
            if (writeOk) {
                [self sendSuccess:@"1" command:command];
            } else {
                [self sendError:@"พิมพ์ผ่าน Xprinter SDK ล้มเหลว" command:command];
            }
        });
    });
}

- (void)openCashDrawer:(CDVInvokedUrlCommand*)command {
    NSString* ipAndPort = [command.arguments objectAtIndex:0];
    if (ipAndPort == nil || [ipAndPort length] == 0) {
        [self sendError:@"ไม่มี IP หรือ Port" command:command];
        return;
    }

    NSString *ip = ipAndPort;
    int port = 9100;
    NSArray *parts = [ipAndPort componentsSeparatedByString:@":"];
    if (parts.count == 2) {
        ip = parts[0];
        port = [parts[1] intValue];
    }

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        int sock = [self connectToPrinter:ip port:port];
        if (sock < 0) {
            [self sendError:@"เชื่อมต่อเครื่องพิมพ์ไม่สำเร็จ" command:command];
            return;
        }

        // ESC p m t1 t2 - เปิดลิ้นชักเก็บเงิน (pin 2)
        const uint8_t openDrawerCmd[] = {0x1B, 0x70, 0x00, 0x19, 0xFA};
        ssize_t sent = send(sock, openDrawerCmd, sizeof(openDrawerCmd), 0);
        close(sock);

        if (sent == sizeof(openDrawerCmd)) {
            [self sendSuccess:@"✅ เปิดลิ้นชักเก็บเงินสำเร็จ" command:command];
        } else {
            [self sendError:@"เปิดลิ้นชักเก็บเงินล้มเหลว" command:command];
        }
    });
}
// - (void)openDrawer:(CDVInvokedUrlCommand*)command {
// -    const uint8_t openDrawerCmd[] = {0x1B, 0x70, 0x00, 0x19, 0xFA};
// -    send(sock, openDrawerCmd, sizeof(openDrawerCmd), 0);
// -}


- (void)clearPrinterQueue:(CDVInvokedUrlCommand*)command {
    NSString* ip = [command.arguments objectAtIndex:0];
    if (ip == nil || [ip length] == 0) {
        [self sendError:@"IP ว่าง" command:command];
        return;
    }

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        BOOL success = [self sendClearCommandToPrinter:ip];
        if (success) {
            [self sendSuccess:@"เคลียร์คิวสำเร็จ" command:command];
        } else {
            [self sendError:@"เคลียร์คิวล้มเหลว" command:command];
        }
    });
}

- (void)checkIpWithPort:(CDVInvokedUrlCommand*)command {
    NSString *ipAndPort = [command.arguments objectAtIndex:0];
    if (!ipAndPort || [ipAndPort length] == 0) {
        [self sendError:@"ไม่มี IP หรือ Port" command:command];
        return;
    }

    NSString *ip = ipAndPort;
    int port = 9100;

    NSArray *parts = [ipAndPort componentsSeparatedByString:@":"];
    if (parts.count == 2) {
        ip = parts[0];
        port = [parts[1] intValue];
    }

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        int sock = [self connectToPrinter:ip port:port];
        if (sock >= 0) {
            close(sock);
            [self sendSuccess:ipAndPort command:command];
        } else {
            [self sendError:@"ไม่พบเครื่องพิมพ์ที่ IP นี้" command:command];
        }
    });
}

//--------------------------------------------//
#pragma mark - Helper Methods

- (void)sendSuccess:(NSString *)message command:(CDVInvokedUrlCommand*)command {
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:message];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)sendError:(NSString *)message command:(CDVInvokedUrlCommand*)command {
    if (!message) {
        message = @"Unknown error";
    }

    NSLog(@"sendError called with message: %@", message);

    CDVPluginResult* pluginResult =
        [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:message];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (BOOL)sendClearCommandToPrinter:(NSString*)ip {
    int sock = [self connectToPrinter:ip port:9100];
    if (sock < 0) return NO;
    const unsigned char clearCmd[] = {0x1B, 0x40}; // ESC @
    ssize_t sent = send(sock, clearCmd, sizeof(clearCmd), 0);
    close(sock);
    return sent == sizeof(clearCmd);
}

- (int)connectToPrinter:(NSString*)ip port:(int)port {
    int sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) {
        NSLog(@"Socket creation failed");
        return -1;
    }

    struct sockaddr_in serv_addr;
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(port);
    serv_addr.sin_addr.s_addr = inet_addr([ip UTF8String]);

    int connectResult = connect(sockfd, (struct sockaddr *)&serv_addr, sizeof(serv_addr));

    if (connectResult < 0) {
        NSLog(@"Connect to printer %@:%d failed", ip, port);
        close(sockfd);
        return -1;
    }
    return sockfd;
}

- (UIImage*)convertToBlackAndWhite:(UIImage*)inputImage {
    CIImage *ciImage = [[CIImage alloc] initWithImage:inputImage];
    CIFilter *bwFilter = [CIFilter filterWithName:@"CIColorMonochrome"];
    [bwFilter setValue:ciImage forKey:kCIInputImageKey];
    [bwFilter setValue:[CIColor colorWithRed:0 green:0 blue:0] forKey:@"inputColor"];
    [bwFilter setValue:@1.0 forKey:@"inputIntensity"];

    CIImage *bwImage = [bwFilter outputImage];
    CIContext *context = [CIContext contextWithOptions:nil];

    CGImageRef bwCGImage = [context createCGImage:bwImage fromRect:bwImage.extent];
    UIImage *finalImage = [UIImage imageWithCGImage:bwCGImage];

    CGImageRelease(bwCGImage);

    return finalImage;
}



- (UIImage*)resizeImage:(UIImage*)image maxWidth:(CGFloat)maxWidth {

    CGFloat oldWidth = image.size.width;
    CGFloat oldHeight = image.size.height;

    if (oldWidth <= maxWidth) {
        return image;
    }

    CGFloat scaleFactor = maxWidth / oldWidth;
    CGFloat newHeight = oldHeight * scaleFactor;
    CGSize newSize = CGSizeMake(maxWidth, newHeight);

    UIGraphicsBeginImageContextWithOptions(newSize, NO, 1.0);

    [image drawInRect:CGRectMake(0, 0, maxWidth, newHeight)];

    UIImage* newImage = UIGraphicsGetImageFromCurrentImageContext();

    UIGraphicsEndImageContext();

    return newImage;

}

- (BOOL)sendImageAsRaster:(UIImage*)image toIP:(NSString*)ip {
    int sock = [self connectToPrinter:ip port:9100];

    if (sock < 0) return NO;

    CGImageRef cgImage = [image CGImage];

    size_t width = CGImageGetWidth(cgImage);
    size_t height = CGImageGetHeight(cgImage);

    size_t widthBytes = (width + 7) / 8; // เพราะต้องเป็นบิต
    uint8_t *bitmapData = (uint8_t *)calloc(widthBytes * height, sizeof(uint8_t));

    if (!bitmapData) {
        close(sock);
        return NO;
    }

    // แปลงเป็น grayscale แล้ว threshold เป็นขาวดำ
    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceGray();
    CGContextRef context = CGBitmapContextCreate(NULL, width, height, 8, width, colorSpace, kCGImageAlphaNone);
    CGColorSpaceRelease(colorSpace);

    if (!context) {
        free(bitmapData);
        close(sock);
        return NO;
    }

    CGContextDrawImage(context, CGRectMake(0, 0, width, height), cgImage);
    uint8_t *grayPixels = CGBitmapContextGetData(context);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {

            int pixelIndex = y * width + x;
            uint8_t gray = grayPixels[pixelIndex];
            int byteIndex = y * widthBytes + x / 8;
            int bitIndex = 7 - (x % 8);

            if (gray < 128) {
                bitmapData[byteIndex] |= (1 << bitIndex); // ดำ
            } else {
                bitmapData[byteIndex] &= ~(1 << bitIndex); // ขาว
            }
        }
    }

    CGContextRelease(context);

    // ESC/POS header
    uint8_t escposHeader[] = {
        0x1D, 0x76, 0x30, 0x00,
        (uint8_t)(widthBytes & 0xFF),
        (uint8_t)((widthBytes >> 8) & 0xFF),
        (uint8_t)(height & 0xFF),
        (uint8_t)((height >> 8) & 0xFF)
    };

    send(sock, escposHeader, sizeof(escposHeader), 0);
    send(sock, bitmapData, widthBytes * height, 0);

    uint8_t cutCmd[] = {0x1B, 0x64, 3, 0x1D, 0x56, 0x41, 0x10};

    send(sock, cutCmd, sizeof(cutCmd), 0);
    free(bitmapData);
    close(sock);

    return YES;
}

//ส่งภาพเป็นชิ้นๆ พร้อม retry
- (BOOL)sendImageChunks:(NSArray<UIImage*>*)chunks toIP:(NSString*)ip {
    for (UIImage *chunk in chunks) {
        BOOL sent = NO;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if ([self sendImageAsRaster:chunk toIP:ip]) {
                sent = YES;
                break;
            } else {
                NSLog(@"⚠️ ชิ้นภาพส่งไม่สำเร็จ (ครั้งที่ %d) รอแล้วลองใหม่", attempt);
                [NSThread sleepForTimeInterval:1.0];
            }
        }

        if (!sent) {
            return NO;
        }
    }

    return YES;

}

/// รอสถานะเครื่องพิมพ์ว่าพร้อมหรือไม่ ภายใน timeout (หน่วย ms)
- (BOOL)waitForPrinterReady:(int)sock timeout:(NSTimeInterval)timeout {
    const uint8_t statusCmd[] = {0x10, 0x04, 0x01}; // DLE EOT 1
    uint8_t response[1] = {0};

    NSDate *startTime = [NSDate date];

    while ([[NSDate date] timeIntervalSinceDate:startTime] * 1000 < timeout) {
        send(sock, statusCmd, sizeof(statusCmd), 0);

        fd_set readfds;

        struct timeval tv;

        FD_ZERO(&readfds);
        FD_SET(sock, &readfds);

        tv.tv_sec = 0;
        tv.tv_usec = 500 * 1000; // 500ms

        int retval = select(sock+1, &readfds, NULL, NULL, &tv);

        if (retval > 0 && FD_ISSET(sock, &readfds)) {
            ssize_t r = recv(sock, response, sizeof(response), 0);
            if (r > 0) {
                NSLog(@"🖨 สถานะเครื่องพิมพ์: 0x%02X", response[0]);
                if ((response[0] & 0x12) == 0x12) {
                    NSLog(@"✅ เครื่องพิมพ์พร้อมแล้ว");
                    return YES;
                }
            }
        }
        [NSThread sleepForTimeInterval:0.5];
    }

    return NO;

}

/// ตัดกระดาษ — feed 5 lines ก่อนแล้ว partial cut
- (void)cutPaper:(int)sock {
    // ESC d 5: feed 5 lines เพื่อให้กระดาษออกมาพอตัด
    const uint8_t feedCmd[] = {0x1B, 0x64, 0x05};
    send(sock, feedCmd, sizeof(feedCmd), 0);
    // GS V 1: partial cut (0x01 = most XPrinter models support this)
    const uint8_t cutCmd[] = {0x1D, 0x56, 0x01};
    send(sock, cutCmd, sizeof(cutCmd), 0);
}

/// ปิด socket อย่างปลอดภัย — รอให้ TCP kernel flush buffer ก่อน
/// send() เป็น non-blocking: data อยู่ใน kernel buffer แต่ยังไม่ส่งออก
/// close() ทันทีทำให้ iOS อาจส่ง TCP RST ก่อน cut bytes ถึงเครื่องพิมพ์ (intermittent ไม่ตัด)
/// SO_LINGER: บังคับให้ close() block จนกว่า TCP จะ ACK ทุก byte หรือ timeout (3 วินาที)
- (void)flushAndClose:(int)sock {
    struct linger lingerOpt;
    lingerOpt.l_onoff = 1;
    lingerOpt.l_linger = 3; // รอสูงสุด 3 วินาที
    setsockopt(sock, SOL_SOCKET, SO_LINGER, &lingerOpt, sizeof(lingerOpt));
    close(sock);
}

/// แบ่ง UIImage ออกเป็นหลายส่วน สูงสุดสูงละ maxHeight px
- (NSArray<UIImage*>*)splitImage:(UIImage*)image maxHeight:(CGFloat)maxHeight {

    NSMutableArray *chunks = [NSMutableArray array];
    CGFloat width = image.size.width;
    CGFloat height = image.size.height;
    CGFloat scale = image.scale;

    for (CGFloat y = 0; y < height; y += maxHeight) {

        CGFloat chunkHeight = MIN(maxHeight, height - y);
        CGRect cropRect = CGRectMake(0, y, width, chunkHeight);

        CGImageRef imageRef = CGImageCreateWithImageInRect(image.CGImage, cropRect);
        UIImage *chunk = [UIImage imageWithCGImage:imageRef scale:scale orientation:image.imageOrientation];

        CGImageRelease(imageRef);

        [chunks addObject:chunk];
    }

    return chunks;

}

- (BOOL)sendImageAsRasterChunk:(UIImage*)image socket:(int)sock {
    CGImageRef cgImage = [image CGImage];

    size_t width = CGImageGetWidth(cgImage);
    size_t height = CGImageGetHeight(cgImage);

    size_t widthBytes = (width + 7) / 8;
    uint8_t *bitmapData = (uint8_t *)calloc(widthBytes * height, sizeof(uint8_t));

    if (!bitmapData) return NO;

    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceGray();
    CGContextRef context = CGBitmapContextCreate(NULL, width, height, 8, width, colorSpace, kCGImageAlphaNone);

    CGColorSpaceRelease(colorSpace);

    if (!context) {
        free(bitmapData);
        return NO;
    }

    CGContextDrawImage(context, CGRectMake(0, 0, width, height), cgImage);

    uint8_t *grayPixels = CGBitmapContextGetData(context);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {

            int pixelIndex = y * width + x;
            uint8_t gray = grayPixels[pixelIndex];
            int byteIndex = y * widthBytes + x / 8;
            int bitIndex = 7 - (x % 8);

            if (gray < 128) {
                bitmapData[byteIndex] |= (1 << bitIndex);
            } else {
                bitmapData[byteIndex] &= ~(1 << bitIndex);
            }
        }
    }

    CGContextRelease(context);

    uint8_t escposHeader[] = {
        0x1D, 0x76, 0x30, 0x00,
        (uint8_t)(widthBytes & 0xFF),
        (uint8_t)((widthBytes >> 8) & 0xFF),
        (uint8_t)(height & 0xFF),
        (uint8_t)((height >> 8) & 0xFF)
    };

    send(sock, escposHeader, sizeof(escposHeader), 0);
    send(sock, bitmapData, widthBytes * height, 0);

    free(bitmapData);
    return YES;
}

- (void)clearPrinter:(int)sock {
    const unsigned char clearCmd[] = {0x1B, 0x40}; // ESC @
    send(sock, clearCmd, sizeof(clearCmd), 0);
}

@end
