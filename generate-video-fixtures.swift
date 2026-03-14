#!/usr/bin/env swift
//
// Generates small video test fixtures for imageio-native-video tests.
//
// Output: 16x16, 3 seconds @ 1fps, H.264 baseline, every frame a keyframe.
//   Frame 0 (t=0s): Solid Red   (#FF0000)
//   Frame 1 (t=1s): Solid Green (#00FF00)
//   Frame 2 (t=2s): Solid Blue  (#0000FF)
//
// Produces:
//   imageio-native-video-common/src/test/resources/test-video-3s.mp4
//   imageio-native-video-common/src/test/resources/test-video-3s.mov
//

import AVFoundation
import CoreMedia
import CoreVideo
import Foundation

let width = 16
let height = 16
let fps: Int32 = 1
let frameCount = 3

// BGRA colors: [B, G, R, A]
let colors: [(String, UInt8, UInt8, UInt8)] = [
    ("Red",   0xFF, 0x00, 0x00),
    ("Green", 0x00, 0xFF, 0x00),
    ("Blue",  0x00, 0x00, 0xFF),
]

func createPixelBuffer(r: UInt8, g: UInt8, b: UInt8) -> CVPixelBuffer {
    var pb: CVPixelBuffer?
    let attrs = [
        kCVPixelBufferWidthKey: width,
        kCVPixelBufferHeightKey: height,
        kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
    ] as CFDictionary
    CVPixelBufferCreate(kCFAllocatorDefault, width, height,
                        kCVPixelFormatType_32BGRA, attrs, &pb)
    guard let pixelBuffer = pb else { fatalError("Failed to create pixel buffer") }

    CVPixelBufferLockBaseAddress(pixelBuffer, [])
    let base = CVPixelBufferGetBaseAddress(pixelBuffer)!
    let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
    for y in 0..<height {
        for x in 0..<width {
            let offset = y * bytesPerRow + x * 4
            base.storeBytes(of: b, toByteOffset: offset + 0, as: UInt8.self)  // B
            base.storeBytes(of: g, toByteOffset: offset + 1, as: UInt8.self)  // G
            base.storeBytes(of: r, toByteOffset: offset + 2, as: UInt8.self)  // R
            base.storeBytes(of: 0xFF, toByteOffset: offset + 3, as: UInt8.self) // A
        }
    }
    CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
    return pixelBuffer
}

func writeVideo(outputURL: URL, fileType: AVFileType) {
    // Remove existing file
    try? FileManager.default.removeItem(at: outputURL)

    guard let writer = try? AVAssetWriter(outputURL: outputURL, fileType: fileType) else {
        fatalError("Failed to create AVAssetWriter for \(outputURL.lastPathComponent)")
    }

    let videoSettings: [String: Any] = [
        AVVideoCodecKey: AVVideoCodecType.h264,
        AVVideoWidthKey: width,
        AVVideoHeightKey: height,
        AVVideoCompressionPropertiesKey: [
            AVVideoProfileLevelKey: AVVideoProfileLevelH264BaselineAutoLevel,
            AVVideoMaxKeyFrameIntervalKey: 1, // every frame is a keyframe
        ],
    ]

    let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
    writerInput.expectsMediaDataInRealTime = false

    let sourcePixelBufferAttributes: [String: Any] = [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: width,
        kCVPixelBufferHeightKey as String: height,
    ]

    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
        assetWriterInput: writerInput,
        sourcePixelBufferAttributes: sourcePixelBufferAttributes)

    writer.add(writerInput)
    writer.startWriting()
    writer.startSession(atSourceTime: .zero)

    for i in 0..<frameCount {
        let (name, r, g, b) = colors[i]
        let pb = createPixelBuffer(r: r, g: g, b: b)
        let time = CMTime(value: CMTimeValue(i), timescale: fps)

        while !writerInput.isReadyForMoreMediaData {
            Thread.sleep(forTimeInterval: 0.01)
        }

        if !adaptor.append(pb, withPresentationTime: time) {
            fatalError("Failed to append frame \(i) (\(name)) at \(time)")
        }
    }

    writerInput.markAsFinished()

    let semaphore = DispatchSemaphore(value: 0)
    writer.finishWriting { semaphore.signal() }
    semaphore.wait()

    if writer.status != .completed {
        fatalError("Writer failed: \(writer.error?.localizedDescription ?? "unknown")")
    }

    let fileSize = (try? FileManager.default.attributesOfItem(atPath: outputURL.path)[.size] as? Int) ?? 0
    print("  \(outputURL.lastPathComponent) (\(fileSize) bytes)")
}

// ── Main ────────────────────────────────────────────────────────────

let outDir = "imageio-native-video-common/src/test/resources"
try FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

print("Generating video fixtures:")
writeVideo(
    outputURL: URL(fileURLWithPath: "\(outDir)/test-video-3s.mp4"),
    fileType: .mp4)
writeVideo(
    outputURL: URL(fileURLWithPath: "\(outDir)/test-video-3s.mov"),
    fileType: .mov)
print("Done")
