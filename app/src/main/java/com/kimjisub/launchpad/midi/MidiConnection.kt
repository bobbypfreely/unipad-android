package com.kimjisub.launchpad.midi

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kimjisub.launchpad.midi.controller.MidiController
import com.kimjisub.launchpad.midi.driver.DriverRef
import com.kimjisub.launchpad.midi.driver.LaunchpadMK2
import com.kimjisub.launchpad.midi.driver.LaunchpadMK3
import com.kimjisub.launchpad.midi.driver.LaunchpadMiniMK3
import com.kimjisub.launchpad.midi.driver.LaunchpadPRO
import com.kimjisub.launchpad.midi.driver.LaunchpadS
import com.kimjisub.launchpad.midi.driver.LaunchpadX
import com.kimjisub.launchpad.midi.driver.MasterKeyboard
import com.kimjisub.launchpad.midi.driver.Matrix
import com.kimjisub.launchpad.midi.driver.MidiFighter
import com.kimjisub.launchpad.midi.driver.Noting
import com.kimjisub.launchpad.tool.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass


object MidiConnection {

	private data class DriverEntry(
		val name: String,
		val factory: () -> DriverRef,
		val interfaceNum: Int = 0,
	)

	// Exact PID matches (non-Novation devices or single-PID devices)
	private val driverRegistryExact: Map<Int, DriverEntry> = mapOf(
		8 to DriverEntry("MidiFighter", ::MidiFighter),
		8211 to DriverEntry("LX 61 piano", ::MasterKeyboard),
		32822 to DriverEntry("Arduino Leonardo midi", ::LaunchpadPRO, interfaceNum = 3),
	)

	// Novation Launchpad PID ranges (Device ID 1~16 -> base PID + 0..15)
	private data class DriverRange(
		val pidStart: Int,
		val pidEnd: Int,
		val entry: DriverEntry,
	)

	private val driverRegistryRanges: List<DriverRange> = listOf(
		DriverRange(0x0020, 0x002F, DriverEntry("Launchpad S", ::LaunchpadS)),           // 32~47
		DriverRange(0x0036, 0x0036, DriverEntry("Launchpad Mini", ::LaunchpadS)),         // 54 (single)
		DriverRange(0x0051, 0x0060, DriverEntry("Launchpad Pro", ::LaunchpadPRO)),        // 81~96
		DriverRange(0x0069, 0x0078, DriverEntry("Launchpad MK2", ::LaunchpadMK2)),        // 105~120
		DriverRange(0x0103, 0x0112, DriverEntry("Launchpad X", ::LaunchpadX)),            // 259~274
		DriverRange(0x0113, 0x0122, DriverEntry("Launchpad Mini MK3", ::LaunchpadMiniMK3)), // 275~290
		DriverRange(0x0123, 0x0132, DriverEntry("Launchpad Pro MK3", ::LaunchpadMK3)),    // 291~306
	)

	private const val MATRIX_PRODUCT_ID_MASK = 0xFFC0
	private const val MATRIX_PRODUCT_ID_BASE = 0x1040

	private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	private const val USB_MIDI_PACKET_SIZE = 4
	private const val USB_BULK_TIMEOUT_MS = 50

	// ---------------------------------------------------------------------
	// DeviceSession holds everything that used to be a single top-level var
	// (USB connection, endpoints, driver instance, send/receive loops).
	// One instance per physically connected Launchpad-like device.
	// ---------------------------------------------------------------------
	private class DeviceSession(val usbDevice: UsbDevice) {
		var usbInterface: UsbInterface? = null
		var usbEndpointIn: UsbEndpoint? = null
		var usbEndpointOut: UsbEndpoint? = null
		var usbDeviceConnection: UsbDeviceConnection? = null

		// Android MIDI API for SysEx delivery (used before USB interface claim)
		var midiDevice: MidiDevice? = null
		val midiInputPorts = mutableMapOf<Int, MidiInputPort>()

		// Deferred USB claim - stored for later use after MIDI API SysEx
		var pendingUsbClaim: (() -> Unit)? = null

		var driver: DriverRef = Noting()

		// Non-blocking ordered send queue: this session's own USB endpoint
		val sendChannel = Channel<ByteArray>(Channel.UNLIMITED)
		var sendJob: Job? = null
		var receiveJob: Job? = null
		@Volatile var isRun = false

		var name: String = "Unknown"
	}

	// Keyed by UsbDevice.deviceId (stable while the device stays attached).
	// ConcurrentHashMap because sessions are mutated on the main thread (initDevice(),
	// disconnect cleanup) but iterated by onSendSignal/onSendRaw, which can run off-main
	// (e.g. LED animation loops) - a plain map here caused ConcurrentModificationException.
	private val sessions = ConcurrentHashMap<Int, DeviceSession>()

	private fun startSendLoop(session: DeviceSession) {
		if (session.sendJob?.isActive == true) return
		session.sendJob = ioScope.launch {
			// Batch buffer: maxPacketSize (64) fits 16 MIDI packets
			val batchBuffer = ByteArray(64)

			for (first in session.sendChannel) {
				try {
					first.copyInto(batchBuffer, 0)
					var offset = USB_MIDI_PACKET_SIZE

					while (offset + USB_MIDI_PACKET_SIZE <= batchBuffer.size) {
						val next = session.sendChannel.tryReceive().getOrNull() ?: break
						next.copyInto(batchBuffer, offset)
						offset += USB_MIDI_PACKET_SIZE
					}

					session.usbDeviceConnection?.bulkTransfer(
						session.usbEndpointOut, batchBuffer, offset, USB_BULK_TIMEOUT_MS
					)
				} catch (_: RuntimeException) {
					// Device may be disconnected
				}
			}
		}
	}

	private var usbManager: UsbManager? = null
	private var midiManager: MidiManager? = null

	private var onCycleListener: DriverRef.OnCycleListener? = null
	private var onReceiveSignalListener: DriverRef.OnReceiveSignalListener? = null
	private var onSendSignalListener: DriverRef.OnSendSignalListener? = null

	// deviceId of whichever session is currently "primary". Tracked explicitly (rather than
	// relying on identity against the public `driver` property) because once a second pad
	// connects, `driver` becomes a MultiplexDriver wrapper rather than a real per-device
	// driver instance - see below.
	@Volatile
	private var primarySessionId: Int? = null

	// Session-only (not persisted). Off: a second connected pad shows an exact copy of the
	// primary's grid. On: the second pad's grid is horizontally flipped (x -> 7 - x), both
	// for what lights up and for which logical pad a physical press maps to - so two units
	// facing each other show/feel like a mirror image instead of a duplicate.
	@Volatile
	var reflectedModeEnabled: Boolean = false

	// Which pad is "primary" (the unflipped reference side) is decided by connection order
	// (whichever connects first), which may not match how the pads are physically placed.
	// If Reflected feels backwards, flip this instead of the connection order.
	@Volatile
	var reflectedSwapSides: Boolean = false

	private fun isFlippedForReflection(session: DeviceSession): Boolean {
		if (!reflectedModeEnabled) return false
		val isPrimary = session.usbDevice.deviceId == primarySessionId
		return if (reflectedSwapSides) isPrimary else !isPrimary
	}

	// Builds a send listener scoped to a single session - it only ever delivers that
	// session's own already-encoded output to that session's own USB/MIDI connection.
	// Fan-out across multiple connected pads is handled one level up, by MultiplexDriver
	// calling each session's own sendPadLed()/etc directly (with per-session coordinates) -
	// NOT by relaying raw bytes here, since raw bytes are already encoded for one specific
	// model and can't be corrected for a differently-encoding second device after the fact.
	private fun makeSendListener(originSession: DeviceSession): DriverRef.OnSendSignalListener =
		object : DriverRef.OnSendSignalListener {
			override fun onSend(cmd: Byte, sig: Byte, note: Byte, velocity: Byte) {
				if (originSession.usbDeviceConnection != null) {
					originSession.sendChannel.trySend(byteArrayOf(cmd, sig, note, velocity))
				}
			}

			override fun onSendRaw(messages: List<ByteArray>, cableNumber: Int) {
				if (originSession.usbDeviceConnection != null) {
					ioScope.launch { sendRawBuffer(originSession, messages, cableNumber) }
				}
			}
		}

	// Builds a receive listener scoped to a single session. Pad touches from the primary
	// session pass straight through. Touches from a non-primary session get their x flipped
	// (7 - x) when reflectedModeEnabled is on, so pressing the pad that's visually lit on the
	// reflected device triggers the same logical pad the primary shows it at.
	private fun makeReceiveListener(session: DeviceSession): DriverRef.OnReceiveSignalListener =
		object : DriverRef.OnReceiveSignalListener {
			override fun onUnknownReceived(cmd: Int, sig: Int, note: Int, velocity: Int) {
				controller?.onUnknownEvent(cmd, sig, note, velocity)
			}

			override fun onPadTouch(x: Int, y: Int, upDown: Boolean, velocity: Int) {
				val mappedX = if (isFlippedForReflection(session)) 7 - x else x
				controller?.onPadTouch(mappedX, y, upDown, velocity)
			}

			override fun onFunctionKeyTouch(f: Int, upDown: Boolean) {
				controller?.onFunctionKeyTouch(f, upDown)
			}

			override fun onChainTouch(c: Int, upDown: Boolean) {
				controller?.onChainTouch(c, upDown)
			}

			override fun onReceived(cmd: Int, sig: Int, note: Int, velocity: Int) {
				controller?.onUnknownEvent(cmd, sig, note, velocity)
			}
		}

	// Exposed as `driver` (see below) once a second pad connects. Fans each write out to
	// every connected session's OWN driver instance, so each device encodes correctly for
	// its own hardware - this is what makes mixed-model pairs (not just identical ones) work,
	// and it's the hook point for reflection (x gets flipped per-session, before encoding).
	private class MultiplexDriver : DriverRef() {
		override fun sendPadLed(x: Int, y: Int, velocity: Int) {
			for (session in MidiConnection.sessions.values) {
				val localX = if (MidiConnection.isFlippedForReflection(session)) 7 - x else x
				session.driver.sendPadLed(localX, y, velocity)
			}
		}

		override fun sendChainLed(c: Int, velocity: Int) {
			for (session in MidiConnection.sessions.values) session.driver.sendChainLed(c, velocity)
		}

		override fun sendFunctionKeyLed(f: Int, velocity: Int) {
			for (session in MidiConnection.sessions.values) session.driver.sendFunctionKeyLed(f, velocity)
		}

		override fun sendClearLed() {
			for (session in MidiConnection.sessions.values) session.driver.sendClearLed()
		}
	}

	@Volatile
	var connectedDevice: ConnectedDeviceSnapshot? = null
		private set

	/** Full list of currently connected devices (e.g. for a "2 Launchpads connected" banner). */
	val connectedDevices: List<ConnectedDeviceSnapshot>
		get() = sessions.values.map { ConnectedDeviceSnapshot(it.name, 0L) }

	@Volatile
	var connectionObserver: ConnectionObserver? = null

	// Dual-pad support is opt-in. Off (default) preserves the original single-device
	// behavior exactly: only ever the first device found gets opened, matching pre-refactor
	// UniPad. On, additional devices connecting are accepted as extra mirrored sessions.
	// Flip this from wherever the user picks their devices, BEFORE plugging them in.
	@Volatile
	var dualPadModeEnabled: Boolean = false

	// `driver` represents the PRIMARY (first-connected) device's driver. PlayActivity /
	// ChannelManager keep reading/writing this exactly as before (e.g. driver.sendPadLed(...)).
	// The mirroring happens underneath: onSendSignalListener broadcasts the resulting bytes to
	// every connected session, so a second (or third) Launchpad lights up in step automatically.
	@Volatile
	private var _driver: DriverRef = Noting()

	var driver: DriverRef
		get() = _driver
		set(value) {
			// A manual pick from MidiSelectActivity while more than one pad is connected
			// must NOT replace the MultiplexDriver dispatcher (that's what was causing only
			// one pad to light up after picking a model). Route it to the primary session
			// specifically via setDriverForSession() instead, and leave the dispatcher in
			// place. To target the secondary pad specifically, call
			// setDriverForSession(sessionId, ...) directly with its session id.
			if (value !is MultiplexDriver && sessions.size > 1 && _driver is MultiplexDriver) {
				primarySessionId?.let { setDriverForSession(it, value) }
				return
			}

			val oldDriver = _driver
			oldDriver.sendClearLed()
			oldDriver.onDisconnected()

			// Write-through only applies to real per-device driver instances (a manual
			// override, or the very first primary assignment) - never to the internal
			// MultiplexDriver wrapper, which must never become a session's own `driver`
			// reference (that would break its receive loop's decoding).
			if (value !is MultiplexDriver) {
				for (session in sessions.values) {
					if (session.driver === oldDriver) {
						session.driver = value
					}
				}
			}

			try {
				_driver = value
				if (value !is MultiplexDriver) {
					val ownerSession = sessions.values.firstOrNull { it.driver === value }
					setDriverListener(
						value,
						ownerSession?.let { makeSendListener(it) } ?: onSendSignalListener,
						ownerSession?.let { makeReceiveListener(it) } ?: onReceiveSignalListener,
					)
				}
				_driver.initialize()
				if (sessions.isNotEmpty())
					_driver.onConnected()
			} catch (e: IllegalAccessException) {
				Log.err("Driver set failed", e)
			} catch (e: InstantiationException) {
				Log.err("Driver instantiation failed", e)
			}

			listener?.onChangeDriver(value)
		}

	@Volatile
	var controller: MidiController? = null

	@Volatile
	internal var listener: Listener? = null
		set(value) {
			field = value
			if (field != null) {
				field?.onChangeDriver(driver)
			}
		}

	fun initConnection(intent: Intent, usbManager: UsbManager, context: Context? = null) {
		this.usbManager = usbManager

		// Initialize Android MIDI API for SysEx support
		if (context != null) {
			midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
			Log.midiDetail("MidiManager available: ${midiManager != null}")
		}

		val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
		} else {
			@Suppress("DEPRECATION")
			intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
		}

		if ("android.hardware.usb.action.USB_DEVICE_ATTACHED" == intent.action) {
			initDevice(usbDevice)
		} else {
			// App-launch / manual scan path. Previously this opened only the FIRST device
			// found; now it opens every attached device that isn't already connected, so
			// both Launchpads get picked up even if they were plugged in before app launch.
			for (device in requireNotNull(usbManager).deviceList.values) {
				if (!sessions.containsKey(device.deviceId)) {
					initDevice(device)
				}
			}
		}

		onCycleListener = object : DriverRef.OnCycleListener {
			override fun onConnected() {
				controller?.onAttach()
			}

			override fun onDisconnected() {
				controller?.onDetach()
			}
		}

		onSendSignalListener = object : DriverRef.OnSendSignalListener {
			override fun onSend(cmd: Byte, sig: Byte, note: Byte, velocity: Byte) {
				// Fallback only - normal wiring uses makeSendListener(session) instead,
				// which is origin-aware and model-guarded. This just avoids a null listener
				// in edge cases (e.g. setDriverListener() called externally with defaults).
				for (session in sessions.values) {
					if (session.driver::class == driver::class && session.usbDeviceConnection != null) {
						session.sendChannel.trySend(byteArrayOf(cmd, sig, note, velocity))
					}
				}
			}

			override fun onSendRaw(messages: List<ByteArray>, cableNumber: Int) {
				for (session in sessions.values) {
					if (session.driver::class == driver::class && session.usbDeviceConnection != null) {
						ioScope.launch {
							sendRawBuffer(session, messages, cableNumber)
						}
					}
				}
			}
		}

		onReceiveSignalListener = object : DriverRef.OnReceiveSignalListener {
			override fun onUnknownReceived(cmd: Int, sig: Int, note: Int, velocity: Int) {
				controller?.onUnknownEvent(cmd, sig, note, velocity)
			}

			override fun onPadTouch(x: Int, y: Int, upDown: Boolean, velocity: Int) {
				controller?.onPadTouch(x, y, upDown, velocity)
			}

			override fun onFunctionKeyTouch(f: Int, upDown: Boolean) {
				controller?.onFunctionKeyTouch(f, upDown)
			}

			override fun onChainTouch(c: Int, upDown: Boolean) {
				controller?.onChainTouch(c, upDown)
			}

			override fun onReceived(cmd: Int, sig: Int, note: Int, velocity: Int) {
				controller?.onUnknownEvent(cmd, sig, note, velocity)
			}
		}

		// Re-wire whichever session(s) were created above now that the listeners exist
		// (mirrors the original ordering, where setDriverListener() was called again at
		// the end of initConnection to finish wiring the driver created inside initDevice()).
		for (session in sessions.values) {
			setDriverListener(session.driver, makeSendListener(session), makeReceiveListener(session))
		}
	}

	private fun initDevice(device: UsbDevice?) {
		if (device == null) {
			Log.midiDetail("USB 에러 : device == null")
			return
		}
		if (sessions.containsKey(device.deviceId)) {
			Log.midiDetail("Device ${device.deviceId} already connected, skipping")
			return
		}
		if (!dualPadModeEnabled && sessions.isNotEmpty()) {
			Log.midiDetail("Dual pad mode is off - ignoring additional device (${device.deviceName})")
			listener?.onUiLog("Dual pad mode is off - ignoring ${device.deviceName}")
			return
		}

		val session = DeviceSession(device)
		var interfaceNum = 0

		try {
			Log.midiDetail("DeviceName : ${device.deviceName}")
			Log.midiDetail("DeviceClass : ${device.deviceClass}")
			Log.midiDetail("DeviceId : ${device.deviceId}")
			Log.midiDetail("DeviceProtocol : ${device.deviceProtocol}")
			Log.midiDetail("DeviceSubclass : ${device.deviceSubclass}")
			Log.midiDetail("InterfaceCount : ${device.interfaceCount}")
			Log.midiDetail("VendorId : ${device.vendorId}")
		} catch (e: SecurityException) {
			Log.err("USB device info read failed", e)
		}

		try {
			Log.midiDetail("ProductId : ${device.productId}")
			listener?.onUiLog("ProductId : ${device.productId}")

			val pid = device.productId
			val exactEntry = driverRegistryExact[pid]
			val rangeEntry = driverRegistryRanges.firstOrNull { pid in it.pidStart..it.pidEnd }?.entry

			val entry = exactEntry ?: rangeEntry
			if (entry != null) {
				val deviceId = if (rangeEntry != null) {
					val range = driverRegistryRanges.first { pid in it.pidStart..it.pidEnd }
					pid - range.pidStart + 1
				} else null
				val idStr = if (deviceId != null) " (Device ID $deviceId)" else ""
				listener?.onUiLog("prediction : ${entry.name}$idStr")
				Log.midiDetail("Driver: ${entry.name}$idStr (PID=0x${"%04X".format(pid)})")
				interfaceNum = entry.interfaceNum
				session.driver = entry.factory()
				session.name = entry.name
			} else if (pid and MATRIX_PRODUCT_ID_MASK == MATRIX_PRODUCT_ID_BASE) {
				listener?.onUiLog("prediction : 203 Matrix")
				session.driver = Matrix()
				session.name = "Matrix"
			} else {
				listener?.onUiLog("prediction : unknown (PID=$pid)")
				session.driver = MasterKeyboard()
				session.name = "Master Keyboard"
			}
		} catch (e: SecurityException) {
			Log.err("USB driver selection failed", e)
		}

		// Log all interfaces
		for (i in 0 until device.interfaceCount) {
			val ui = device.getInterface(i)
			Log.midiDetail("Interface[$i]: class=${ui.interfaceClass}, subclass=${ui.interfaceSubclass}, endpoints=${ui.endpointCount}")
		}

		// Find MIDI Streaming interface (class=1, subclass=3)
		for (i in interfaceNum until device.interfaceCount) {
			val ui = device.getInterface(i)
			if (ui.endpointCount > 0 && ui.interfaceClass == UsbConstants.USB_CLASS_AUDIO && ui.interfaceSubclass == 3) {
				session.usbInterface = ui
				listener?.onUiLog("Interface MIDI : (${i + 1}/${device.interfaceCount})")
				break
			}
		}
		// Fallback: first interface with endpoints
		if (session.usbInterface == null) {
			for (i in interfaceNum until device.interfaceCount) {
				val ui = device.getInterface(i)
				if (ui.endpointCount > 0) {
					session.usbInterface = ui
					listener?.onUiLog("Interface : (${i + 1}/${device.interfaceCount})")
					break
				}
			}
		}
		val usbIf = session.usbInterface ?: run {
			Log.midiDetail("USB 에러 : usbInterface == null")
			return
		}
		for (i in 0 until usbIf.endpointCount) {
			val ep = usbIf.getEndpoint(i)
			val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
			val info = "EP[$i] dir=$dir type=${ep.type} addr=0x${"%02X".format(ep.address)} maxPkt=${ep.maxPacketSize}"
			Log.midiDetail(info)
			listener?.onUiLog(info)
			when (ep.direction) {
				UsbConstants.USB_DIR_IN -> session.usbEndpointIn = ep
				UsbConstants.USB_DIR_OUT -> session.usbEndpointOut = ep
			}
		}
		val manager = usbManager ?: run {
			Log.midiDetail("USB 에러 : usbManager == null")
			return
		}
		val connection = manager.openDevice(device)
		if (connection == null) {
			Log.midiDetail("USB 에러 : usbDeviceConnection == null")
			return
		}
		session.usbDeviceConnection = connection

		// Defer USB interface claim - MIDI API needs the interface first for SysEx
		session.pendingUsbClaim = {
			Log.midiDetail("USB: Claiming interface for MIDI communication")
			if (connection.claimInterface(usbIf, true)) {
				startReceiveLoop(session)
			} else {
				Log.midiDetail("USB 에러 : claimInterface failed")
			}
		}

		sessions[device.deviceId] = session
		setDriverListener(session.driver, makeSendListener(session), makeReceiveListener(session))
		session.driver.initialize()

		// First device to connect becomes "primary". PlayActivity/ChannelManager keep
		// reading/writing `driver` exactly as before. With only one pad connected, `driver`
		// is that pad's own real driver instance - zero overhead/behavior change for the
		// vast majority of users who never use dual-pad mode. Once a second pad connects,
		// `driver` becomes a MultiplexDriver that fans writes out to every session's own
		// driver (each encoding correctly for its own hardware, flipping x per-session when
		// Reflected mode is on).
		if (sessions.size == 1) {
			primarySessionId = device.deviceId
			driver = session.driver
			publishConnectedDevice(session.name)
		} else {
			session.driver.onConnected()
			if (sessions.size == 2) {
				driver = MultiplexDriver()
			}
		}

		listener?.onConnectedListener()
		connectedDevice?.let { connectionObserver?.onConnected(it) }

		// Try MIDI API for SysEx first, then claim USB interface
		initMidiApiDevice(session, device)
	}

	private fun initMidiApiDevice(session: DeviceSession, usbDevice: UsbDevice?) {
		val manager = midiManager
		if (manager == null || usbDevice == null) {
			Log.midiDetail("MIDI API not available, claiming USB interface directly")
			claimUsbAndStart(session)
			return
		}

		@Suppress("DEPRECATION")
		val deviceInfos = manager.devices
		Log.midiDetail("MIDI API: ${deviceInfos.size} device(s) found")

		val targetInfo = deviceInfos.firstOrNull { info ->
			val props = info.properties
			Log.midiDetail("MIDI API device: name=${props.getString(MidiDeviceInfo.PROPERTY_NAME)}, " +
				"manufacturer=${props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)}, " +
				"product=${props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)}, " +
				"inputPorts=${info.inputPortCount}, outputPorts=${info.outputPortCount}")
			info.inputPortCount > 0
		}

		if (targetInfo == null) {
			Log.midiDetail("MIDI API: No matching MIDI device found, claiming USB interface directly")
			claimUsbAndStart(session)
			return
		}

		Log.midiDetail("MIDI API: Opening device (inputPorts=${targetInfo.inputPortCount}, outputPorts=${targetInfo.outputPortCount})")
		for (port in targetInfo.ports) {
			val dir = if (port.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) "INPUT" else "OUTPUT"
			Log.midiDetail("  MIDI API Port[${port.portNumber}]: $dir name=${port.name}")
		}

		manager.openDevice(targetInfo, { device ->
			if (device != null) {
				session.midiDevice = device
				Log.midiDetail("MIDI API: Device opened successfully")

				// Open all input ports and send SysEx
				for (portInfo in targetInfo.ports) {
					if (portInfo.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) {
						val port = device.openInputPort(portInfo.portNumber)
						if (port != null) {
							session.midiInputPorts[portInfo.portNumber] = port
							Log.midiDetail("MIDI API: Opened input port ${portInfo.portNumber} (${portInfo.name})")
						}
					}
				}

				// Send SysEx to ALL input ports (port names are empty, we don't know which is DAW)
				val initData = session.driver.getInitSysEx()
				if (initData != null && session.midiInputPorts.isNotEmpty()) {
					val (messages, _) = initData
					for ((portNum, _) in session.midiInputPorts) {
						Log.midiDetail("MIDI API: Sending init SysEx (${messages.size} messages) to port $portNum")
						sendViaMidiApi(session, messages, portNum)
					}
				}

				// Delay to ensure SysEx is flushed before closing ports
				Handler(Looper.getMainLooper()).postDelayed({
					closeMidiApi(session)
					claimUsbAndStart(session)
				}, 500)
			} else {
				Log.midiDetail("MIDI API: Failed to open device, claiming USB interface directly")
				claimUsbAndStart(session)
			}
		}, Handler(Looper.getMainLooper()))
	}

	private fun closeMidiApi(session: DeviceSession) {
		Log.midiDetail("MIDI API: Closing ports and device")
		for ((portNum, port) in session.midiInputPorts) {
			try {
				port.close()
				Log.midiDetail("MIDI API: Closed input port $portNum")
			} catch (e: Exception) {
				Log.err("MIDI API: Failed to close port $portNum", e)
			}
		}
		session.midiInputPorts.clear()
		try {
			session.midiDevice?.close()
			session.midiDevice = null
			Log.midiDetail("MIDI API: Device closed")
		} catch (e: Exception) {
			Log.err("MIDI API: Failed to close device", e)
		}
	}

	private fun claimUsbAndStart(session: DeviceSession) {
		session.pendingUsbClaim?.invoke()
		session.pendingUsbClaim = null
		startSendLoop(session)
	}

	private fun sendViaMidiApi(session: DeviceSession, messages: List<ByteArray>, cableNumber: Int): Boolean {
		val port = session.midiInputPorts[cableNumber] ?: return false
		try {
			for ((index, msg) in messages.withIndex()) {
				val hex = msg.joinToString(" ") { "%02X".format(it) }
				Log.midiDetail("MIDI API TX (port=$cableNumber): $hex")
				port.send(msg, 0, msg.size)
				// Delay between SysEx messages to allow device mode transitions
				if (index < messages.size - 1) {
					Thread.sleep(50)
				}
			}
			return true
		} catch (e: Exception) {
			Log.err("MIDI API send failed", e)
			return false
		}
	}

	private fun sendRawBuffer(session: DeviceSession, messages: List<ByteArray>, cableNumber: Int = 0) {
		// Try Android MIDI API first (handles SysEx properly)
		if (sendViaMidiApi(session, messages, cableNumber)) {
			Log.midiDetail("SysEx sent via MIDI API (port=$cableNumber)")
			return
		}

		// Fallback: USB bulk transfer with manual SysEx encoding
		Log.midiDetail("MIDI API not available for port=$cableNumber, falling back to USB bulk transfer")
		try {
			for ((index, msg) in messages.withIndex()) {
				val encoded = encodeSysEx(msg, cableNumber)
				Log.midiDetail("TX SysEx (USB): ${msg.joinToString(" ") { "%02X".format(it) }} (cable=$cableNumber)")

				var offset = 0
				while (offset < encoded.size) {
					val chunk = minOf(64, encoded.size - offset)
					session.usbDeviceConnection?.bulkTransfer(
						session.usbEndpointOut, encoded, offset, chunk, USB_BULK_TIMEOUT_MS
					)
					offset += chunk
				}
				// Delay between SysEx messages to allow device mode transitions
				if (index < messages.size - 1) {
					Thread.sleep(50)
				}
			}
		} catch (e: RuntimeException) {
			Log.err("sendRawBuffer failed", e)
		}
	}

	private fun encodeSysEx(sysex: ByteArray, cableNumber: Int = 0): ByteArray {
		val cablePrefix = (cableNumber shl 4).toByte()
		val packets = mutableListOf<Byte>()
		var i = 0
		while (i < sysex.size) {
			val remaining = sysex.size - i
			if (remaining >= 3 && sysex[i + 2] != 0xF7.toByte()) {
				// SysEx start or continue: CIN = 0x04
				packets.add((cablePrefix + 0x04).toByte())
				packets.add(sysex[i])
				packets.add(sysex[i + 1])
				packets.add(sysex[i + 2])
				i += 3
			} else if (remaining == 1) {
				// SysEx end with 1 byte: CIN = 0x05
				packets.add((cablePrefix + 0x05).toByte())
				packets.add(sysex[i])
				packets.add(0x00)
				packets.add(0x00)
				i += 1
			} else if (remaining == 2) {
				// SysEx end with 2 bytes: CIN = 0x06
				packets.add((cablePrefix + 0x06).toByte())
				packets.add(sysex[i])
				packets.add(sysex[i + 1])
				packets.add(0x00)
				i += 2
			} else {
				// SysEx end with 3 bytes: CIN = 0x07
				packets.add((cablePrefix + 0x07).toByte())
				packets.add(sysex[i])
				packets.add(sysex[i + 1])
				packets.add(sysex[i + 2])
				i += 3
			}
		}
		return packets.toByteArray()
	}

	private fun startReceiveLoop(session: DeviceSession) {
		session.receiveJob?.cancel()
		session.receiveJob = ioScope.launch {
			withContext(Dispatchers.Main) {
				session.driver.onConnected()
			}

			if (!session.isRun) {
				session.isRun = true
				Log.midiDetail("USB 시작 (${session.name})")

				val endpointIn = session.usbEndpointIn ?: run {
					Log.midiDetail("USB 에러 : usbEndpointIn == null")
					session.isRun = false
					return@launch
				}

				var prevTime = SystemClock.elapsedRealtime()
				var count = 0
				val byteArray = ByteArray(endpointIn.maxPacketSize)
				// Flat int array: [cmd0,sig0,note0,vel0, cmd1,sig1,note1,vel1, ...]
				val eventBuf = IntArray(endpointIn.maxPacketSize)

				while (session.isRun) {
					try {
						val conn = session.usbDeviceConnection ?: break
						val length = conn.bulkTransfer(
							endpointIn,
							byteArray,
							byteArray.size,
							1000
						)
						if (length >= 4) {
							var eventCount = 0
							var i = 0
							while (i < length) {
								val b1 = byteArray[i + 1].toInt() and 0xFF
								if (b1 == 0xF8) { // Skip MIDI Clock
									i += 4
									continue
								}
								val base = eventCount * 4
								eventBuf[base] = byteArray[i].toInt()
								eventBuf[base + 1] = byteArray[i + 1].toInt()
								eventBuf[base + 2] = byteArray[i + 2].toInt()
								eventBuf[base + 3] = byteArray[i + 3].toInt()
								eventCount++
								i += 4
							}
							if (eventCount > 0) {
								// Copy to snapshot for safe Main thread dispatch
								val snapshot = eventBuf.copyOf(eventCount * 4)
								val n = eventCount
								withContext(Dispatchers.Main) {
									for (j in 0 until n) {
										val base = j * 4
										session.driver.getSignal(snapshot[base], snapshot[base + 1], snapshot[base + 2], snapshot[base + 3])
									}
								}
							}
						} else if (length == -1) {
							val currTime = SystemClock.elapsedRealtime()
							if (prevTime != currTime) {
								count = 0
								prevTime = currTime
							} else {
								count++
								if (count > 10)
									break
							}
						}
					} catch (e: RuntimeException) {
						Log.err("MIDI receive loop error", e)
						break
					}
				}

				Log.midiDetail("USB 끝 (${session.name})")
			}
			session.isRun = false

			withContext(Dispatchers.Main) {
				session.driver.onDisconnected()
				sessions.remove(session.usbDevice.deviceId)

				val wasPrimary = session.usbDevice.deviceId == primarySessionId
				if (wasPrimary) {
					primarySessionId = sessions.values.firstOrNull()?.usbDevice?.deviceId
				}

				when {
					sessions.isEmpty() -> {
						driver = Noting()
					}
					sessions.size == 1 && driver is MultiplexDriver -> {
						// Back down to one pad - drop the multiplex wrapper and talk to
						// that pad's own driver directly again.
						driver = sessions.values.first().driver
					}
					wasPrimary && driver !is MultiplexDriver -> {
						// Single-pad mode, and the connected pad just disconnected -
						// promote whichever other pad is left, if any.
						driver = sessions.values.firstOrNull()?.driver ?: Noting()
					}
					// else: MultiplexDriver stays in place (2+ pads still connected), or a
					// non-primary pad disconnected without affecting the primary - nothing
					// else to update.
				}

				connectedDevice = sessions.values.firstOrNull()?.let {
					ConnectedDeviceSnapshot(it.name, SystemClock.elapsedRealtime())
				}

				if (sessions.isEmpty()) {
					connectionObserver?.onDisconnected()
				}
			}
		}
	}

	private fun publishConnectedDevice(name: String) {
		connectedDevice = ConnectedDeviceSnapshot(
			name = name,
			eventId = SystemClock.elapsedRealtime()
		)
	}

	// Driver

	fun setDriverListener(
		target: DriverRef = driver,
		sendListener: DriverRef.OnSendSignalListener? = onSendSignalListener,
		receiveListener: DriverRef.OnReceiveSignalListener? = onReceiveSignalListener,
	) {
		target.setOnCycleListener(onCycleListener)
		target.setOnGetSignalListener(receiveListener)
		target.setOnSendSignalListener(sendListener)
	}

	// Read-only snapshot of every currently connected pad, for UI that wants to let the
	// person pick a model per physical device (rather than only ever targeting the primary).
	data class SessionSummary(
		val sessionId: Int,
		val deviceName: String,
		val driverClass: KClass<out DriverRef>,
		val isPrimary: Boolean,
	)

	val connectedSessions: List<SessionSummary>
		get() = sessions.values.map {
			SessionSummary(
				sessionId = it.usbDevice.deviceId,
				deviceName = it.name,
				driverClass = it.driver::class,
				isPrimary = it.usbDevice.deviceId == primarySessionId,
			)
		}

	// Sets the model/driver for one specific connected pad, identified by SessionSummary.sessionId.
	// Unlike assigning `driver` directly, this always targets exactly the requested physical
	// device and never disturbs the MultiplexDriver dispatcher other pads rely on - this is
	// the one to use from a per-device picker UI.
	fun setDriverForSession(sessionId: Int, value: DriverRef) {
		val target = sessions[sessionId] ?: return

		val oldTargetDriver = target.driver
		oldTargetDriver.sendClearLed()
		oldTargetDriver.onDisconnected()

		target.driver = value
		setDriverListener(value, makeSendListener(target), makeReceiveListener(target))
		try {
			value.initialize()
			value.onConnected()
		} catch (e: IllegalAccessException) {
			Log.err("Driver set failed", e)
		} catch (e: InstantiationException) {
			Log.err("Driver instantiation failed", e)
		}

		// Keep the public `driver` property in sync when there's only one pad connected
		// (no MultiplexDriver in play) and this is that pad.
		if (sessionId == primarySessionId && _driver !is MultiplexDriver) {
			_driver = value
		}

		listener?.onChangeDriver(value)
	}

	// Controller

	fun removeController(target: MidiController) {
		if (controller != null && controller === target)
			controller = null
	}

	interface Listener {
		fun onConnectedListener()

		fun onChangeDriver(driverRef: DriverRef)

		fun onUiLog(log: String)
	}

	interface ConnectionObserver {
		fun onConnected(snapshot: ConnectedDeviceSnapshot)
		fun onDisconnected()
	}

	data class ConnectedDeviceSnapshot(
		val name: String,
		val eventId: Long,
	)
}