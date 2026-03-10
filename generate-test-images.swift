#!/usr/bin/env swift
//
// Generates test fixtures for the Panama ImageIO reader tests.
//
// Usage:  swift generate-test-images.swift
//
// Produces in src/test/resources/:
//   test4x4.png   – baseline via CGImageDestination
//   test4x4.heic  – via CGImageDestination
//   test4x4.avif  – via CGImageDestination
//   test4x4.webp  – via WKWebView Canvas.toDataURL (CGImageDestination can't write WebP)
//

import Foundation
import CoreGraphics
import ImageIO
import WebKit

// ── Configuration ───────────────────────────────────────────────────

let width  = 4
let height = 4
let dir    = "src/test/resources"

// ── Create 4×4 RGBA test image: red | green / blue | white ─────────

var pixels = [UInt8](repeating: 0, count: width * height * 4)
for y in 0..<height {
    for x in 0..<width {
        let i = (y * width + x) * 4
        switch (y < 2, x < 2) {
        case (true,  true):  pixels[i] = 255; pixels[i+1] = 0;   pixels[i+2] = 0;   pixels[i+3] = 255  // red
        case (true,  false): pixels[i] = 0;   pixels[i+1] = 255; pixels[i+2] = 0;   pixels[i+3] = 255  // green
        case (false, true):  pixels[i] = 0;   pixels[i+1] = 0;   pixels[i+2] = 255; pixels[i+3] = 255  // blue
        case (false, false): pixels[i] = 255; pixels[i+1] = 255; pixels[i+2] = 255; pixels[i+3] = 255  // white
        }
    }
}

let colorSpace = CGColorSpaceCreateDeviceRGB()
guard let ctx = CGContext(data: &pixels, width: width, height: height,
                          bitsPerComponent: 8, bytesPerRow: width * 4,
                          space: colorSpace,
                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
      let cgImage = ctx.makeImage() else {
    fatalError("Failed to create CGImage")
}

// ── Helpers ─────────────────────────────────────────────────────────

try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)

func writeCG(image: CGImage, uti: String, to path: String, options: CFDictionary? = nil) {
    let url = URL(fileURLWithPath: path)
    guard let dest = CGImageDestinationCreateWithURL(url as CFURL, uti as CFString, 1, nil) else {
        fatalError("CGImageDestination does not support \(uti)")
    }
    CGImageDestinationAddImage(dest, image, options)
    guard CGImageDestinationFinalize(dest) else {
        fatalError("Failed to finalize \(path)")
    }
    print("  \(path)")
}

// ── Generate PNG, HEIC, AVIF via CGImageDestination ────────────────

let losslessOpts = [kCGImageDestinationLossyCompressionQuality: 1.0] as CFDictionary

print("Generating test images:")
writeCG(image: cgImage, uti: "public.png",  to: "\(dir)/test4x4.png")
writeCG(image: cgImage, uti: "public.heic", to: "\(dir)/test4x4.heic", options: losslessOpts)
writeCG(image: cgImage, uti: "public.avif", to: "\(dir)/test4x4.avif")  // default opts – avif encoder dislikes quality=1 on tiny images

// ── WebP via WKWebView Canvas (with fallback) ─────────────────────
// CGImageDestination cannot encode WebP.  We try WKWebView's
// Canvas.toDataURL('image/webp') first; some WebKit builds silently
// fall back to PNG, so we verify the RIFF/WEBP magic and use a
// pre-built VP8L lossless blob as fallback.

let webpFallback: [UInt8] = [
    0x52, 0x49, 0x46, 0x46, 0x2c, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4c,
    0x1f, 0x00, 0x00, 0x00, 0x2f, 0x03, 0xc0, 0x00, 0x00, 0x1f, 0x20, 0x10, 0x48, 0xde, 0x1f, 0x3a,
    0x8d, 0xf9, 0x17, 0x10, 0x14, 0xfc, 0x1f, 0xdd, 0xfc, 0x47, 0x64, 0x0f, 0xe0, 0x5a, 0xf0, 0x11,
    0xfd, 0x0f, 0x06, 0x00,
]

func isWebP(_ data: Data) -> Bool {
    data.count >= 12
        && data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46  // RIFF
        && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50 // WEBP
}

class WebPGenerator: NSObject, WKNavigationDelegate {
    var done = false
    var resultData: Data?

    func run(outputPath: String) {
        let config = WKWebViewConfiguration()
        let handler = MessageHandler { base64 in
            self.resultData = Data(base64Encoded: base64)
            self.done = true
        }
        config.userContentController.add(handler, name: "done")

        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 16, height: 16), configuration: config)
        webView.navigationDelegate = self

        let html = """
        <canvas id="c" width="4" height="4"></canvas>
        <script>
        const x = document.getElementById('c').getContext('2d');
        x.fillStyle='#ff0000'; x.fillRect(0,0,2,2);
        x.fillStyle='#00ff00'; x.fillRect(2,0,2,2);
        x.fillStyle='#0000ff'; x.fillRect(0,2,2,2);
        x.fillStyle='#ffffff'; x.fillRect(2,2,2,2);
        window.webkit.messageHandlers.done.postMessage(
            document.getElementById('c').toDataURL('image/webp').split(',')[1]
        );
        </script>
        """
        webView.loadHTMLString(html, baseURL: nil)

        let timeout = Date(timeIntervalSinceNow: 10)
        while !done && Date() < timeout {
            RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.05))
        }

        let data: Data
        if let d = resultData, isWebP(d) {
            data = d
            print("  \(outputPath) (via WKWebView Canvas)")
        } else {
            data = Data(webpFallback)
            print("  \(outputPath) (embedded VP8L – WebKit did not produce WebP)")
        }
        try! data.write(to: URL(fileURLWithPath: outputPath))
    }

    func webView(_ wv: WKWebView, didFail nav: WKNavigation!, withError error: Error) {
        done = true // let fallback handle it
    }
}

class MessageHandler: NSObject, WKScriptMessageHandler {
    let callback: (String) -> Void
    init(_ callback: @escaping (String) -> Void) { self.callback = callback }
    func userContentController(_ uc: WKUserContentController, didReceive msg: WKScriptMessage) {
        callback(msg.body as! String)
    }
}

WebPGenerator().run(outputPath: "\(dir)/test4x4.webp")

print("Done – 4 files")
