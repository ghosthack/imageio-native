#!/usr/bin/env swift
//
// Generates HEIC and AVIF test fixtures via macOS CGImageDestination.
//
// Usage:  swift generate-heic-avif-cgimage.swift
//
// Note: 8×8 is used rather than 4×4 because the Windows HEVC and AV1
// codec extensions cannot decode images smaller than 8×8 pixels.
//

import Foundation
import CoreGraphics
import ImageIO

// ── Configuration ───────────────────────────────────────────────────

let width  = 8
let height = 8
let dir = "imageio-native-common/src/test/resources"

// ── Create 8×8 RGBA test image: red | green / blue | white ─────────

var pixels = [UInt8](repeating: 0, count: width * height * 4)
for y in 0..<height {
    for x in 0..<width {
        let i = (y * width + x) * 4
        switch (y < height / 2, x < width / 2) {
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

print("Generating HEIC + AVIF test images:")
writeCG(image: cgImage, uti: "public.heic", to: "\(dir)/test8x8.heic", options: losslessOpts)
writeCG(image: cgImage, uti: "public.avif", to: "\(dir)/test8x8.avif")  // default opts – avif encoder dislikes quality=1 on tiny images

print("Done – 2 files in \(dir)")
