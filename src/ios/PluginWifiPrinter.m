#import "PluginWifiPrinter.h"
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>

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
    NSString* ipAndPort = [command.arguments objectAtIndex:0];
    NSString* base64String = [command.arguments objectAtIndex:1];
    
    if (ipAndPort == nil || base64String == nil || [ipAndPort length] == 0 || [base64String length] == 0) {
        [self sendError:@"IP หรือ Base64 ว่าง" command:command];
        return;
    }

    // 🌟 แยก IP และ Port
    NSString *ip = ipAndPort;
    int port = 9100;
    NSArray *parts = [ipAndPort componentsSeparatedByString:@":"];
    if (parts.count == 2) {
        ip = parts[0];
        port = [parts[1] intValue];
    }

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSData *imageData = [[NSData alloc] initWithBase64EncodedString:base64String options:NSDataBase64DecodingIgnoreUnknownCharacters];
        if (imageData == nil) {
            [self sendError:@"ไม่สามารถแปลง Base64 เป็น NSData ได้" command:command];
            return;
        }
        UIImage *image = [UIImage imageWithData:imageData];
        if (image == nil) {
            [self sendError:@"ไม่สามารถแปลง NSData เป็น UIImage ได้" command:command];
            return;
        }

        UIImage *processedImage = [self resizeImage:image maxWidth:576];
        UIImage *bwImage = [self convertToBlackAndWhite:processedImage];

        int sock = [self connectToPrinter:ip port:port];
        if (sock < 0) {
            [self sendError:@"เชื่อมต่อเครื่องพิมพ์ไม่สำเร็จ" command:command];
            return;
        }

        NSArray<UIImage*> *chunks = [self splitImage:bwImage maxHeight:500]; // แบ่งเป็น 500px ต่อส่วน
        BOOL allSent = YES;

        for (UIImage *chunk in chunks) {
            BOOL sent = NO;
            for (int attempt = 1; attempt <= 3; attempt++) {
                if ([self sendImageAsRasterChunk:chunk socket:sock]) {
                    sent = YES;
                    break;
                } else {
                    NSLog(@"⚠️ ส่ง chunk ล้มเหลว (ครั้งที่ %d) รอแล้วลองใหม่", attempt);
                    [NSThread sleepForTimeInterval:1.0];
                }
            }

            if (!sent) {
                allSent = NO;
                break;
            }
        }

        if (allSent) {
            NSLog(@"⏳ รอสถานะเครื่องพิมพ์จนกว่าจะพร้อม…");
            BOOL printerReady = NO;
            int retry = 0, maxRetry = 60;

            while (retry++ < maxRetry) {
                if ([self waitForPrinterReady:sock timeout:1000]) {
                    printerReady = YES;
                    break;
                } else {
                    NSLog(@"⏳ เครื่องพิมพ์ยังไม่พร้อม (ครั้งที่ %d/%d)", retry, maxRetry);
                    [NSThread sleepForTimeInterval:1.0];
                }
            }

            if (printerReady) {
                [self cutPaper:sock];
                close(sock);
                [self sendSuccess:@"✅ พิมพ์สำเร็จและตัดกระดาษแล้ว" command:command];
            } else {
                [self cutPaper:sock];
                close(sock);
                [self sendError:@"⚠️ เครื่องพิมพ์ไม่ตอบสนองหลังรอนานเกินไป" command:command];
            }

        } else {
            close(sock);
            [self sendError:@"พิมพ์บางส่วนล้มเหลว" command:command];
        }
    });
}


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

/// ตัดกระดาษ
- (void)cutPaper:(int)sock {
    const uint8_t cutCmd[] = {0x1D, 0x56, 0x41, 0x10};
    send(sock, cutCmd, sizeof(cutCmd), 0);
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
