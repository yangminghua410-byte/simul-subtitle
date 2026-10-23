// Captures what the Mac plays (all processes except this one) with a Core Audio process tap (macOS 14.2+), or the
// default microphone with --mic, and writes it to stdout as 16 kHz mono float32 little-endian, the format the
// pipeline's Segmenter takes.
//   sysaudio [--mic] [seconds]    --mic: capture the default microphone; <seconds>: stop after (default: until stdin
//                                 closes or SIGINT/SIGTERM)
// The first run asks for "System Audio Recording" permission (playback) or "Microphone" permission (--mic), for the
// terminal app that started it.
import AVFoundation
import CoreAudio
import Foundation

func fail(_ what: String, _ status: OSStatus) -> Never {
    FileHandle.standardError.write("sysaudio: \(what) failed (\(status))\n".data(using: .utf8)!)
    exit(1)
}

func property<T>(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector, _ value: inout T) -> OSStatus {
    var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                             mElement: kAudioObjectPropertyElementMain)
    var size = UInt32(MemoryLayout<T>.size)
    return AudioObjectGetPropertyData(object, &address, 0, nil, &size, &value)
}

let mic = CommandLine.arguments.contains("--mic")
let seconds: Double? = CommandLine.arguments.dropFirst().first { !$0.hasPrefix("-") }.flatMap { Double($0) }

// 1. the capture device: the default microphone (--mic), or a tap on every process's output except our own
var tap = AudioObjectID(kAudioObjectUnknown) // only the playback tap; the aggregate holds it as its input
var device = AudioObjectID(kAudioObjectUnknown)
var isTap = false
var status = noErr
if mic {
    var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice,
                                             mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var size = UInt32(MemoryLayout<AudioObjectID>.size)
    status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device)
    if status != noErr || device == kAudioObjectUnknown { fail("default input device", status != noErr ? status : OSStatus(-1)) }
} else {
    var ownProcess = AudioObjectID(kAudioObjectUnknown)
    var pid = getpid()
    var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyTranslatePIDToProcessObject,
                                             mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var size = UInt32(MemoryLayout<AudioObjectID>.size)
    _ = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, UInt32(MemoryLayout<pid_t>.size), &pid,
                                   &size, &ownProcess)
    let tapDescription = CATapDescription(stereoGlobalTapButExcludeProcesses: ownProcess == kAudioObjectUnknown ? [] : [ownProcess])
    tapDescription.uuid = UUID()
    tapDescription.isPrivate = true
    tapDescription.muteBehavior = .unmuted
    status = AudioHardwareCreateProcessTap(tapDescription, &tap)
    if status != noErr { fail("AudioHardwareCreateProcessTap", status) }

    // a private aggregate device that has only the tap as input
    let aggregateUID = UUID().uuidString
    let description: [String: Any] = [
        kAudioAggregateDeviceNameKey: "simul-subtitle capture",
        kAudioAggregateDeviceUIDKey: aggregateUID,
        kAudioAggregateDeviceIsPrivateKey: true,
        kAudioAggregateDeviceIsStackedKey: false,
        kAudioAggregateDeviceTapAutoStartKey: true,
        kAudioAggregateDeviceTapListKey: [[kAudioSubTapUIDKey: tapDescription.uuid.uuidString,
                                           kAudioSubTapDriftCompensationKey: true]],
    ]
    var aggregate = AudioObjectID(kAudioObjectUnknown)
    status = AudioHardwareCreateAggregateDevice(description as CFDictionary, &aggregate)
    if status != noErr { fail("AudioHardwareCreateAggregateDevice", status) }
    device = aggregate
    isTap = true
}

func cleanUp() {
    if isTap {
        AudioHardwareDestroyAggregateDevice(device)
        AudioHardwareDestroyProcessTap(tap)
    }
}

// 2. the device's stream format (the tap's format, or the microphone's input format)
var format = AudioStreamBasicDescription()
if mic {
    var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreamFormat,
                                             mScope: kAudioObjectPropertyScopeInput, mElement: kAudioObjectPropertyElementMain)
    var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
    status = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &format)
    if status != noErr { fail("mic stream format", status) }
} else {
    status = property(tap, kAudioTapPropertyFormat, &format)
    if status != noErr { fail("tap format", status) }
}

// 3. convert to 16 kHz mono float32 and write
guard let inFormat = AVAudioFormat(streamDescription: &format),
      let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false),
      let converter = AVAudioConverter(from: inFormat, to: outFormat)
else { cleanUp(); fail("audio format", -1) }
let out = FileHandle.standardOutput
let queue = DispatchQueue(label: "sysaudio")
var procID: AudioDeviceIOProcID?
status = AudioDeviceCreateIOProcIDWithBlock(&procID, device, queue) { _, input, _, _, _ in
    guard let inBuffer = AVAudioPCMBuffer(pcmFormat: inFormat, bufferListNoCopy: input, deallocator: nil) else { return }
    let capacity = AVAudioFrameCount(Double(inBuffer.frameLength) * 16_000 / inFormat.sampleRate) + 16
    guard let outBuffer = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: capacity) else { return }
    var given = false
    var error: NSError?
    converter.convert(to: outBuffer, error: &error) { _, inputStatus in
        if given { inputStatus.pointee = .noDataNow; return nil }
        given = true; inputStatus.pointee = .haveData; return inBuffer
    }
    if error != nil || outBuffer.frameLength == 0 { return }
    let data = Data(bytes: outBuffer.floatChannelData![0], count: Int(outBuffer.frameLength) * 4)
    do { try out.write(contentsOf: data) } catch { cleanUp(); exit(0) } // reader gone
}
if status != noErr { cleanUp(); fail("AudioDeviceCreateIOProcIDWithBlock", status) }
status = AudioDeviceStart(device, procID)
if status != noErr { cleanUp(); fail("AudioDeviceStart", status) }
FileHandle.standardError.write("sysaudio: capturing \(mic ? "microphone" : "system audio") \(Int(inFormat.sampleRate)) Hz × \(inFormat.channelCount) → 16 kHz mono\n".data(using: .utf8)!)

func stop() -> Never {
    AudioDeviceStop(device, procID)
    if let procID { AudioDeviceDestroyIOProcID(device, procID) }
    cleanUp()
    exit(0)
}
signal(SIGINT, SIG_IGN); signal(SIGTERM, SIG_IGN)
let sources = [SIGINT, SIGTERM].map { DispatchSource.makeSignalSource(signal: $0, queue: .main) }
sources.forEach { $0.setEventHandler { stop() }; $0.resume() }
if let seconds { DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { stop() } }
dispatchMain()
