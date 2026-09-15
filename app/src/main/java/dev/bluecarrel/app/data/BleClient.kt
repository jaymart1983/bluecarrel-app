package dev.bluecarrel.app.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.TimeZone
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A reader seen advertising the transfer service, for the pairing picker. */
data class DiscoveredReader(
    /** Bluetooth address, upper case. Pairing connects to exactly this. */
    val address: String,
    /** The advertised name, or "Reader" when none was sent. */
    val name: String,
    /** Last received signal strength, dBm. */
    val rssi: Int,
)

/**
 * BLE GATT client for the Bluecarrel reader's transfer service.
 *
 * The reader is the GATT *peripheral*; this app is the central. There is no
 * WiFi on the device, so this is the only transport.
 *
 * Wire format is not guessed: it was read out of the 1.6.0 firmware image's
 * string table and cross-checked against the vendor's own Web Bluetooth
 * companion at https://ble.xteink.lol (referenced by the firmware itself as
 * `browser_companion_url`). Notable details that differ from a naive reading:
 *
 *  - `start_put` names the file in a field called **`name`**, not `filename`.
 *  - Every data frame on data-in / data-out is `uint32 little-endian sequence`
 *    followed by the payload — 4 bytes of header, sequence starting at 0.
 *  - Upload flow control is credit-based: the client declares `ack_bytes` in
 *    `start_put` and must pause until the status JSON reports `received` has
 *    caught up, otherwise the reader's event queue overflows.
 *  - Security v2 (protocol_version 2, bluecarrel-firmware docs/security-v2.md):
 *    the link is bonded and encrypted (LE Secure Connections, passkey entry),
 *    and on top of it a mutual HMAC-SHA256 keyed with the 32 RAW secret bytes.
 *    The session is authorised only after the reader's `reader_proof` verifies.
 *
 * NONE of this has been tested against real hardware.
 */
class BleClient(private val context: Context) {

    companion object {
        val SERVICE: UUID = UUID.fromString("6f9f0a00-9b1d-4d1f-9f53-5b6b8b3d0f10")
        private val CONTROL: UUID = UUID.fromString("6f9f0a01-9b1d-4d1f-9f53-5b6b8b3d0f10")
        private val DATA_IN: UUID = UUID.fromString("6f9f0a02-9b1d-4d1f-9f53-5b6b8b3d0f10")
        private val STATUS: UUID = UUID.fromString("6f9f0a03-9b1d-4d1f-9f53-5b6b8b3d0f10")
        private val DATA_OUT: UUID = UUID.fromString("6f9f0a04-9b1d-4d1f-9f53-5b6b8b3d0f10")

        /** Client Characteristic Configuration Descriptor. */
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Frame header: uint32 LE sequence number. */
        const val FRAME_HEADER_BYTES = 4

        /** Payload cap the reference client uses; the reader bounds this too. */
        const val MAX_CHUNK_BYTES = 500

        /** Credit window before we must wait for the reader to catch up. */
        const val ACK_BYTES = 24_000

        /** The reference client's download chunk size. */
        const val DOWNLOAD_CHUNK_BYTES = 160

        private const val DEFAULT_MTU = 23
        private const val OP_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val SCAN_TIMEOUT_MS = 15_000L

        /** How long the pairing picker scans before it says nothing was found. */
        const val DISCOVERY_MS = 12_000L
        private const val COMMIT_TIMEOUT_MS = 120_000L

        /** The user reads the passkey off the reader and types it. */
        private const val BOND_TIMEOUT_MS = 60_000L

        /** The reader must speak security v2. */
        const val PROTOCOL_VERSION = 2

        /** The reader rejects anything this does not match ("unsafe ... filename"). */
        private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")

        fun isSafeTransferName(name: String): Boolean =
            SAFE_NAME.matches(name) && !name.contains('/') && !name.contains('\\')

        fun newHostSecret(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.toHex()
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        /** 16 random bytes as 32 lowercase hex: a hello's client_nonce. */
        fun newClientNonce(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.toHex()
        }

        /** Hex to bytes; null for an odd length or a non-hex character. */
        fun hexToBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        /**
         * HMAC-SHA256 keyed with the 32 raw bytes of [secretHex], over
         * "X4AUTH2|<role>|D|C|H|I": D the reader's device_nonce, C the client
         * nonce, H the host id, I the reader's device_id. [role] is "host" for the
         * hello response and "reader" for the proof the reader returns.
         */
        fun authMac(
            secretHex: String,
            role: String,
            deviceNonce: String,
            clientNonce: String,
            hostId: String,
            deviceId: String,
        ): ByteArray {
            val key = hexToBytes(secretHex)?.takeIf { it.size == 32 }
                ?: throw IllegalArgumentException("host secret must be 64 hex characters")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(
                "X4AUTH2|$role|$deviceNonce|$clientNonce|$hostId|$deviceId".toByteArray(Charsets.UTF_8)
            )
        }

        /** Constant-time check of a hex reader_proof against the expected MAC. */
        fun proofMatches(expected: ByteArray, proofHex: String): Boolean {
            val got = hexToBytes(proofHex.trim()) ?: return false
            return MessageDigest.isEqual(expected, got)
        }

        /** Reader errors meaning this session is not, or no longer, authenticated. */
        fun isAuthError(code: String?): Boolean =
            code != null && (code.contains("session") || code.contains("auth") ||
                code.contains("trusted host") || code.contains("pair"))

        /**
         * How an ordinary upload ends. `save_host_prompt` is deliberately not
         * terminal: the reader is asking the user, on its own screen, whether
         * to remember this phone, and the transfer is not finished until they
         * answer. That answer is also the only way pairing ever becomes
         * permanent, so waiting through it is the point.
         */
        val DEFAULT_COMMIT_DONE: (DeviceStatus) -> Boolean = {
            it.state == "saved" || it.state == "installed" || it.state == "error"
        }

        /**
         * How a `catalog_page` / `catalog_detail` upload ends — which is not
         * `saved`.
         *
         * The firmware's commit handler special-cases the catalogue kinds: it
         * renames the staged blob, hands it to `BleStoreController::
         * onCatalogCommitted` and calls `resetTransfer(false)` **without**
         * `completeFinalState`, so `state` stays `receiving` and no `saved`
         * ever arrives. Waiting for one would block for the whole 120 s commit
         * timeout on a transfer that actually succeeded. What does change is
         * `pending`: the controller clears it whether the container parsed or
         * was refused. So the disappearance of our request is the completion
         * signal.
         *
         * Note the consequence: a container the device rejects (bad JSON, a
         * truncated cover) is indistinguishable here from one it accepted. The
         * reader shows that failure on its own Store screen with a Retry hint,
         * and re-asks with a new `req`, which is the recovery path — so this
         * app reports "delivered", not "displayed".
         */
        fun catalogCommitDone(req: Int): (DeviceStatus) -> Boolean = {
            it.state == "error" || it.pending?.req != req
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- state

    private val _connection = MutableStateFlow(BleConnection.IDLE)
    val connection: StateFlow<BleConnection> = _connection.asStateFlow()

    private val _status = MutableStateFlow<DeviceStatus?>(null)
    val status: StateFlow<DeviceStatus?> = _status.asStateFlow()

    /**
     * Every status notification, in order. Command helpers subscribe to this
     * *before* writing so a fast reader cannot answer into the gap.
     */
    private val statusUpdates = MutableSharedFlow<DeviceStatus>(extraBufferCapacity = 128)

    private val dataOutFrames = MutableSharedFlow<DataFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<DataFrame> = dataOutFrames.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var dataIn: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var dataOut: BluetoothGattCharacteristic? = null

    @Volatile private var mtu: Int = DEFAULT_MTU
    @Volatile private var authorized: Boolean = false

    /** The device_id the verified hello was made with. A status naming another reader drops authorisation. */
    @Volatile private var authedDeviceId: String? = null

    /** Serializes GATT operations: the stack allows exactly one in flight. */
    private val opLock = Mutex()

    /** Serializes whole transfers, so two uploads can never interleave. */
    private val transferLock = Mutex()

    /**
     * Serialises trusted authentication: one hello in flight, ever.
     *
     * The reader ROTATES device_nonce each time it accepts a hello, and a hello
     * computed against a spent nonce is refused -- whereupon the reader's
     * setAuthError() clears trustedHostName_ and helloAccepted_, DE-authenticating
     * a session that had just succeeded.
     *
     * Two callers race for this: connectReader() and the connection observer's
     * reauthenticate(). Both read the same nonce and both send a hello; the first
     * is accepted and rotates the nonce, the second is refused and tears the
     * authorisation back down. The app is then left believing it is authorised --
     * the first attempt really did succeed -- while the reader shows no BLE and
     * its Store asks for a phone.
     */
    private val authLock = Mutex()

    private var connectGate: CompletableDeferred<Unit>? = null
    private var servicesGate: CompletableDeferred<Unit>? = null
    private var mtuGate: CompletableDeferred<Int>? = null
    private var ioGate: CompletableDeferred<ByteArray>? = null

    /**
     * Devices the picker scan saw, by upper-case address. Pairing connects to the
     * picked one straight from here: the scan result carries the address type, and
     * a second scan would spend one of Android's few scan starts per 30 s on a
     * device that was just seen.
     */
    private val discovered = java.util.concurrent.ConcurrentHashMap<String, BluetoothDevice>()

    /**
     * A human-readable trace of the last trusted-auth attempt, for the
     * diagnostics panel.
     *
     * Exists because this bug has survived four fixes aimed at code that was
     * either already correct or never reached. The reader's serial log proves it
     * accepts the phone; what could not be seen from outside was which branch of
     * this client ran afterwards. There is no adb on this machine, so the app has
     * to be able to say what it did.
     */
    @Volatile
    var lastAuthTrace: String = "no attempt yet"
        private set

    /** The last auth_error the reader reported, from any status read or notification. */
    @Volatile
    var lastAuthError: String? = null
        private set

    /** Short reason the last pair or hello did not authorise; null after a success. */
    @Volatile
    var lastAuthFailure: String? = null
        private set

    data class DataFrame(val sequence: Long, val payload: ByteArray) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = sequence.hashCode()
    }

    /**
     * Why a link attempt failed, in the terms the UI has to act on.
     *
     * Classifying at the throw site rather than by matching on message text
     * later: "Bluetooth is off" and "the reader is not on its Transfer screen"
     * need completely different things from the user, and a substring match on
     * a message that someone later rewords fails silently.
     */
    enum class Reason {
        /** No Bluetooth hardware at all. Nothing to offer the user. */
        NO_ADAPTER,

        /** The radio is switched off. */
        BLUETOOTH_OFF,

        /** Scanning was refused — usually a permission or a throttled scan. */
        SCAN_FAILED,

        /** Nothing advertising the service. The reader is off, out of range,
         *  or not on its Bluetooth Transfer screen. */
        NOT_FOUND,

        /** Connected, but it does not speak this protocol. */
        NOT_A_READER,

        /** The link itself failed: GATT error, dropped connection. */
        LINK,

        /** The reader refused our credentials. */
        AUTH,

        /** Android bonding did not complete, or the phone is not bonded with the reader. */
        BOND,

        /** The reader's protocol_version is below 2. */
        OLD_FIRMWARE,

        /** The reader refused an operation. [BleException.code] has its word. */
        PROTOCOL,

        OTHER,
    }

    class BleException(
        message: String,
        val code: String? = null,
        val reason: Reason = Reason.OTHER,
    ) : Exception(message)

    // ------------------------------------------------------------ callbacks

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connectGate?.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val why =
                        if (statusCode == BluetoothGatt.GATT_SUCCESS) "Reader disconnected"
                        else "Reader disconnected (GATT status $statusCode)"
                    // Closing here matters: an unclosed BluetoothGatt holds a
                    // client interface, and the stack only has a handful.
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    teardown(BleException(why))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) servicesGate?.complete(Unit)
            else servicesGate?.completeExceptionally(
                BleException("Service discovery failed (GATT status $statusCode)")
            )
        }

        override fun onMtuChanged(g: BluetoothGatt, negotiated: Int, statusCode: Int) {
            // A refused MTU request is survivable: we just send smaller frames.
            mtuGate?.complete(if (statusCode == BluetoothGatt.GATT_SUCCESS) negotiated else DEFAULT_MTU)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) ioGate?.complete(ByteArray(0))
            else ioGate?.completeExceptionally(
                BleException("Write to ${c.uuid} failed (GATT status $statusCode)")
            )
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            d: BluetoothGattDescriptor,
            statusCode: Int,
        ) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) ioGate?.complete(ByteArray(0))
            else ioGate?.completeExceptionally(
                BleException("Enabling notifications failed (GATT status $statusCode)")
            )
        }

        // API 33+ delivers the value as a parameter; older releases mutate the
        // characteristic. Overriding both without a version guard would double
        // up on some stacks, so each overload handles only its own range.

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            statusCode: Int,
        ) = completeRead(c, value, statusCode)

        @Deprecated("Pre-API-33 delivery path", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                completeRead(c, c.value ?: ByteArray(0), statusCode)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = dispatchNotification(c, value)

        @Deprecated("Pre-API-33 delivery path", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                dispatchNotification(c, c.value ?: ByteArray(0))
            }
        }
    }

    private fun completeRead(c: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
        if (statusCode == BluetoothGatt.GATT_SUCCESS) {
            if (c.uuid == STATUS) publishStatus(value)
            ioGate?.complete(value)
        } else {
            ioGate?.completeExceptionally(
                BleException("Read of ${c.uuid} failed (GATT status $statusCode)")
            )
        }
    }

    private fun dispatchNotification(c: BluetoothGattCharacteristic, value: ByteArray) {
        when (c.uuid) {
            STATUS -> publishStatus(value)
            DATA_OUT -> {
                if (value.size < FRAME_HEADER_BYTES) return
                val seq = ByteBuffer.wrap(value, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
                    .int.toLong() and 0xFFFFFFFFL
                dataOutFrames.tryEmit(
                    DataFrame(seq, value.copyOfRange(FRAME_HEADER_BYTES, value.size))
                )
            }
        }
    }

    private fun publishStatus(raw: ByteArray) {
        val parsed = DeviceStatus.parse(String(raw, Charsets.UTF_8)) ?: return
        parsed.authError?.let { lastAuthError = it }
        // A status can take authorisation away, never grant it: only a verified
        // reader_proof does that (authenticateLocked).
        if (parsed.state == "error" && isAuthError(parsed.error)) authorized = false
        if (parsed.authError != null && !parsed.trustedHost) authorized = false
        val authedId = authedDeviceId
        if (authedId != null && parsed.deviceId != null && parsed.deviceId != authedId) authorized = false
        _status.value = parsed
        statusUpdates.tryEmit(parsed)
    }

    private fun teardown(cause: BleException) {
        connectGate?.completeExceptionally(cause)
        servicesGate?.completeExceptionally(cause)
        mtuGate?.completeExceptionally(cause)
        ioGate?.completeExceptionally(cause)
        control = null; dataIn = null; statusChar = null; dataOut = null
        authorized = false
        authedDeviceId = null
        mtu = DEFAULT_MTU
        _connection.value = BleConnection.IDLE
    }

    // ------------------------------------------------------------ lifecycle

    private fun adapter(): BluetoothAdapter {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw BleException("This phone has no Bluetooth radio", reason = Reason.NO_ADAPTER)
        val a = manager.adapter
            ?: throw BleException("This phone has no Bluetooth adapter", reason = Reason.NO_ADAPTER)
        if (!a.isEnabled) {
            throw BleException("Bluetooth is off", reason = Reason.BLUETOOTH_OFF)
        }
        return a
    }

    /** Whether the radio is on right now, without throwing. For the UI. */
    fun bluetoothEnabled(): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)

    /**
     * Scan, connect, bond, set up.
     *
     * Connects to [address] and nothing else -- never "the first reader found".
     * A device the picker scan saw at that address is used directly; otherwise
     * that address, plus the service UUID, is scanned for.
     * [allowBond] lets an unbonded reader be bonded here, with Android's system
     * dialog taking the passkey the reader shows. Without it an unbonded reader
     * is refused, so a background reconnect never raises a pairing dialog.
     *
     * Refuses a reader below protocol_version 2.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, allowBond: Boolean = false): DeviceStatus {
        if (_connection.value == BleConnection.CONNECTED &&
            gatt?.device?.address.equals(address, ignoreCase = true)
        ) {
            return readStatus()
        }
        disconnect()
        val seen = discovered.remove(address.uppercase())
        _connection.value = if (seen != null) BleConnection.CONNECTING else BleConnection.SCANNING
        try {
            val device = seen ?: scanForReader(address)
            _connection.value = BleConnection.CONNECTING

            connectGate = CompletableDeferred()
            val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BleException("Could not open a GATT connection")
            gatt = g
            withTimeout(CONNECT_TIMEOUT_MS) { connectGate!!.await() }

            // Bonded before anything else: every characteristic needs an
            // encrypted, authenticated link.
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                if (!allowBond) {
                    throw BleException("This phone is not paired with the reader", reason = Reason.BOND)
                }
                _connection.value = BleConnection.BONDING
                ensureBonded(device)
                _connection.value = BleConnection.CONNECTING
            }

            // MTU first: the negotiated value decides our frame size, and
            // renegotiating after discovery is not reliable across vendors.
            mtuGate = CompletableDeferred()
            if (g.requestMtu(517)) {
                mtu = withTimeoutOrNull(OP_TIMEOUT_MS) { mtuGate!!.await() } ?: DEFAULT_MTU
            }

            servicesGate = CompletableDeferred()
            if (!g.discoverServices()) throw BleException("Could not start service discovery")
            withTimeout(OP_TIMEOUT_MS) { servicesGate!!.await() }

            val service = g.getService(SERVICE)
                ?: throw BleException(
                    "That device does not expose the Bluecarrel transfer service",
                    reason = Reason.NOT_A_READER,
                )
            control = service.getCharacteristic(CONTROL)
            dataIn = service.getCharacteristic(DATA_IN)
            statusChar = service.getCharacteristic(STATUS)
            dataOut = service.getCharacteristic(DATA_OUT)
            if (control == null || dataIn == null || statusChar == null || dataOut == null) {
                throw BleException(
                    "The reader's transfer service is missing a characteristic",
                    reason = Reason.NOT_A_READER,
                )
            }

            enableNotifications(statusChar!!)
            enableNotifications(dataOut!!)

            val initial = readStatus()
            if ((initial.protocolVersion ?: 0) < PROTOCOL_VERSION) {
                throw BleException("This reader needs Bluecarrel firmware", reason = Reason.OLD_FIRMWARE)
            }
            _connection.value = BleConnection.CONNECTED
            return initial
        } catch (e: Throwable) {
            disconnect()
            throw if (e is BleException) e
            else BleException(
                e.message ?: "Could not connect to the reader",
                reason = Reason.LINK,
            )
        }
    }

    /**
     * Readers advertising the transfer service nearby, for the pairing picker.
     *
     * Emits the whole list whenever it changes -- one entry per Bluetooth
     * address, strongest signal first -- starting with an empty one. Scans at low
     * latency, filtered to [SERVICE], for [durationMs], then completes; cancelling
     * the collector stops the scan sooner. Fails with a [BleException]
     * (NO_ADAPTER, BLUETOOTH_OFF, SCAN_FAILED) the same way [connect] does. The
     * caller checks the runtime permission first.
     */
    @SuppressLint("MissingPermission")
    fun discoverReaders(durationMs: Long = DISCOVERY_MS): Flow<List<DiscoveredReader>> = callbackFlow {
        val scanner = adapter().bluetoothLeScanner
            ?: throw BleException("Bluetooth scanning is unavailable", reason = Reason.SCAN_FAILED)
        val seen = HashMap<String, DiscoveredReader>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val key = device.address?.uppercase() ?: return
                val snapshot = synchronized(seen) {
                    // A scan response can arrive without the name the advertisement carried.
                    val name = result.scanRecord?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: runCatching { device.name }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: seen[key]?.name
                        ?: "Reader"
                    seen[key] = DiscoveredReader(key, name, result.rssi)
                    discovered[key] = device
                    seen.values.sortedByDescending { it.rssi }
                }
                trySend(snapshot)
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("Bluetooth scan failed (error $errorCode)", reason = Reason.SCAN_FAILED))
            }
        }
        trySend(emptyList())
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb,
            )
        } catch (e: Exception) {
            throw BleException(e.message ?: "Could not start a Bluetooth scan", reason = Reason.SCAN_FAILED)
        }
        val timer = launch {
            delay(durationMs)
            channel.close()
        }
        awaitClose {
            timer.cancel()
            runCatching { scanner.stopScan(cb) }
        }
    }.conflate()

    /** The connected reader's Bluetooth address, or null. */
    @SuppressLint("MissingPermission")
    fun connectedAddress(): String? = gatt?.device?.address

    /**
     * Bonds with [device] and waits for ACTION_BOND_STATE_CHANGED to report
     * BONDED. Android's system dialog asks for the passkey on the reader's screen.
     */
    @SuppressLint("MissingPermission")
    private suspend fun ensureBonded(device: BluetoothDevice) {
        val done = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = IntentCompat.getParcelableExtra(
                    intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                )
                if (d == null || !d.address.equals(device.address, ignoreCase = true)) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                if (state == BluetoothDevice.BOND_BONDED ||
                    (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING)
                ) {
                    done.complete(state)
                }
            }
        }
        // Exported on purpose: ACTION_BOND_STATE_CHANGED is a protected broadcast
        // from the Bluetooth process, and NOT_EXPORTED below API 33 adds a
        // permission that sender does not hold. Registered before createBond so
        // no event can be missed.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                throw BleException("Could not start pairing", reason = Reason.BOND)
            }
            val deadline = System.currentTimeMillis() + BOND_TIMEOUT_MS
            while (true) {
                val state = withTimeoutOrNull(500) { done.await() } ?: device.bondState
                if (state == BluetoothDevice.BOND_BONDED) return
                if (done.isCompleted) throw BleException("Pairing failed", reason = Reason.BOND)
                if (gatt == null) throw BleException("The reader dropped the link while pairing", reason = Reason.BOND)
                if (System.currentTimeMillis() > deadline) {
                    throw BleException("Pairing timed out", reason = Reason.BOND)
                }
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Not suspending on purpose: it must be callable from ViewModel teardown. */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        val g = gatt
        gatt = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        teardown(BleException("Disconnected"))
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForReader(address: String): BluetoothDevice {
        val scanner = adapter().bluetoothLeScanner
            ?: throw BleException("Bluetooth scanning is unavailable", reason = Reason.SCAN_FAILED)
        return withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var settled = false
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (settled) return
                        if (!result.device.address.equals(address, ignoreCase = true)) return
                        settled = true
                        runCatching { scanner.stopScan(this) }
                        cont.resume(result.device)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (settled) return
                        settled = true
                        cont.resumeWithException(
                            BleException(
                                "Bluetooth scan failed (error $errorCode)",
                                reason = Reason.SCAN_FAILED,
                            )
                        )
                    }
                }
                cont.invokeOnCancellation {
                    settled = true
                    runCatching { scanner.stopScan(cb) }
                }
                runCatching {
                    scanner.startScan(
                        listOf(
                            ScanFilter.Builder()
                                .setServiceUuid(ParcelUuid(SERVICE))
                                .setDeviceAddress(address.uppercase())
                                .build()
                        ),
                        ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build(),
                        cb,
                    )
                }.onFailure {
                    settled = true
                    cont.resumeWithException(
                        BleException(
                            it.message ?: "Could not start a Bluetooth scan",
                            reason = Reason.SCAN_FAILED,
                        )
                    )
                }
            }
        } ?: throw BleException(
            "No Bluecarrel reader is advertising",
            reason = Reason.NOT_FOUND,
        )
    }

    /**
     * Android needs BOTH halves: the local flag so the stack forwards the
     * notification, and the CCCD write so the peripheral actually sends it.
     * Doing only the first is the classic "notifications never arrive" bug.
     */
    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(c: BluetoothGattCharacteristic) {
        val g = gatt ?: throw BleException("Not connected")
        if (!g.setCharacteristicNotification(c, true)) {
            throw BleException("Could not subscribe to ${c.uuid}")
        }
        val cccd = c.getDescriptor(CCCD)
            ?: throw BleException("Characteristic ${c.uuid} has no CCCD descriptor")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        opLock.withLock {
            val gate = CompletableDeferred<ByteArray>()
            ioGate = gate
            @Suppress("DEPRECATION")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
            if (!started) {
                ioGate = null
                throw BleException("Bluetooth stack refused the CCCD write for ${c.uuid}")
            }
            withTimeout(OP_TIMEOUT_MS) { gate.await() }
        }
    }

    // ------------------------------------------------------------- plumbing

    @SuppressLint("MissingPermission")
    suspend fun readStatus(): DeviceStatus {
        val g = gatt ?: throw BleException("Not connected")
        val c = statusChar ?: throw BleException("Not connected")
        val bytes = opLock.withLock {
            val gate = CompletableDeferred<ByteArray>()
            ioGate = gate
            if (!g.readCharacteristic(c)) {
                ioGate = null
                throw BleException("Bluetooth stack refused the status read")
            }
            withTimeout(OP_TIMEOUT_MS) { gate.await() }
        }
        // Parse the bytes THIS read returned, not _status.value. That is shared
        // state every arriving notification overwrites, so a notification
        // landing between the read response and this line would replace the
        // answer. That is not a rare race: the reader notifies on every state
        // change, and a notification is capped at ATT_MTU-3 and sheds fields to
        // fit, `trusted_host` among them -- so a read meant to confirm
        // authentication could come back without the field and send the app
        // back into a hello it did not need.
        return DeviceStatus.parse(String(bytes, Charsets.UTF_8))
            ?: throw BleException("Reader returned an unreadable status")
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeChar(
        c: BluetoothGattCharacteristic,
        data: ByteArray,
        withoutResponse: Boolean,
    ) {
        val g = gatt ?: throw BleException("Not connected")
        val type =
            if (withoutResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        opLock.withLock {
            val gate = CompletableDeferred<ByteArray>()
            ioGate = gate
            @Suppress("DEPRECATION")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = data
                g.writeCharacteristic(c)
            }
            if (!started) {
                ioGate = null
                throw BleException("Bluetooth stack refused a write to ${c.uuid}")
            }
            withTimeout(OP_TIMEOUT_MS) { gate.await() }
        }
    }

    /** Asks for Android's shortest connection interval. Best effort: a refusal only costs speed. */
    @SuppressLint("MissingPermission")
    private fun requestFastLink() {
        runCatching { gatt?.requestConnectionPriority(android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
    }

    private suspend fun writeControl(command: JSONObject) {
        val c = control ?: throw BleException("Not connected")
        writeChar(c, command.toString().toByteArray(Charsets.UTF_8), withoutResponse = false)
    }

    /**
     * Writes [command] and waits for the first status notification satisfying
     * [predicate]. Subscription happens before the write, so a reader that
     * answers instantly cannot be missed.
     */
    private suspend fun commandAwait(
        command: JSONObject,
        timeoutMs: Long,
        predicate: (DeviceStatus) -> Boolean,
    ): DeviceStatus = withTimeoutOrNull(timeoutMs) {
        statusUpdates.onSubscription { writeControl(command) }.first(predicate)
    } ?: throw BleException(
        "Reader did not answer \"${command.optString("op")}\" in time"
    )

    /**
     * Waits on the *StateFlow*, not the notification stream: StateFlow replays
     * its current value to a new collector, so there is no window between
     * "check the latest status" and "start listening" in which an update can
     * be lost. Only safe for monotone predicates (bytes received, terminal
     * states) because StateFlow conflates.
     */
    private suspend fun awaitStatus(
        timeoutMs: Long,
        predicate: (DeviceStatus) -> Boolean,
    ): DeviceStatus = withTimeoutOrNull(timeoutMs) {
        _status.filterNotNull().first(predicate)
    } ?: throw BleException("Reader stopped responding mid-transfer")

    private fun failIfError(s: DeviceStatus) {
        if (s.state != "error") return
        val reason = if (isAuthError(s.error)) Reason.AUTH else Reason.PROTOCOL
        throw BleException(friendlyError(s.error), s.error, reason)
    }

    /**
     * Tells the reader the time, and the zone it is in.
     *
     * The reader has no network and therefore no NTP: HalClock starts from the
     * firmware's BUILD epoch, which keeps saved positions stamped but is UTC and
     * is only ever a lower bound. Nothing was ever sending this, so the clock
     * read as hours wrong -- exactly the UTC offset of wherever the user was.
     *
     * The offset is quarter-hours biased by 48 (48 = UTC+0), matching
     * CrossPointSettings::clockUtcOffsetQ. Quarters because not every zone is a
     * whole hour, and taken from the phone at the moment of sending so DST is
     * whatever the phone currently believes.
     */
    suspend fun setDeviceTime() {
        val now = System.currentTimeMillis()
        val offsetMinutes = TimeZone.getDefault().getOffset(now) / 60_000
        val quarters = Math.round(offsetMinutes / 15.0).toInt() + 48
        writeControl(
            JSONObject()
                .put("op", "set_time")
                .put("epoch", now / 1000)
                .put("utc_offset_q", quarters.coerceIn(0, 96)),
        )
    }

    // ---------------------------------------------------------------- auth

    /**
     * Drops the belief that this session is authenticated.
     *
     * Needed because authenticate() suppresses a second hello while
     * `authorized` is true: without a way to clear it, a session the reader has
     * forgotten (it rebooted) can never be re-authenticated.
     */
    fun markUnauthorized() {
        authorized = false
        authedDeviceId = null
    }

    /**
     * Stores this phone on the reader:
     *
     *     {"op":"pair","version":2,"host_id":H,"host_name":N,"secret":"<64 hex>"}
     *
     * Sent only on a connected link Android reports BOND_BONDED, and only while
     * the reader's status says `pairing_window`. The answer authorises nothing;
     * the caller follows with [authenticate], whose reader_proof does.
     *
     * Returns only when the reader confirms `paired == true`, in the awaited
     * notification or a fresh read. Anything else throws.
     */
    @SuppressLint("MissingPermission")
    suspend fun pair(identity: HostIdentity): DeviceStatus = authLock.withLock {
        val trace = StringBuilder()
        fun note(s: String) { trace.append(s).append('\n'); lastAuthTrace = trace.toString().trim() }
        note("pair host=${identity.hostId.take(8)}…")
        lastAuthFailure = null
        val device = gatt?.device ?: run {
            note("pair: not connected")
            lastAuthFailure = "not connected"
            throw BleException("Not connected", reason = Reason.LINK)
        }
        if (_connection.value != BleConnection.CONNECTED || device.bondState != BluetoothDevice.BOND_BONDED) {
            note("pair: not bonded")
            lastAuthFailure = "not bonded"
            throw BleException("This phone is not paired with the reader", reason = Reason.BOND)
        }
        val pre = readStatus()
        if (!pre.pairingWindow) {
            note("pair: pairing_window=false")
            lastAuthFailure = "pairing window closed"
            throw BleException(friendlyError("pairing window closed"), "pairing window closed", Reason.AUTH)
        }
        val command = JSONObject()
            .put("op", "pair")
            .put("version", PROTOCOL_VERSION)
            .put("host_id", identity.hostId)
            .put("host_name", identity.hostName)
            .put("secret", identity.secret)
        val awaitedResult = runCatching {
            commandAwait(command, 8_000) { it.paired || it.authError != null || it.state == "error" }
        }
        val awaited = awaitedResult.getOrNull()
        note("pair await=" + awaitedResult.fold(
            { "paired=${it.paired} auth_error=${it.authError ?: "-"}" },
            { "timeout/err: ${it.message}" },
        ))
        val verdict = runCatching { readStatus() }.getOrNull()
        note("pair verdict paired=${verdict?.paired} auth_error=${verdict?.authError ?: "-"}")
        if (verdict != null && verdict.paired) return@withLock verdict
        if (awaited != null && awaited.paired) return@withLock verdict ?: awaited
        val refusal = verdict?.authError ?: awaited?.authError
        lastAuthFailure = refusal ?: "not confirmed"
        note("pair RESULT confirmed=false")
        if (refusal != null) throw BleException(friendlyError(refusal), refusal, Reason.AUTH)
        throw BleException("The reader did not confirm pairing", reason = Reason.AUTH)
    }

    /** Authenticates with a v2 hello, unless this session already is. */
    suspend fun authenticate(identity: HostIdentity): Boolean = authLock.withLock {
        // Whoever lost the race has nothing to do: the session is already through
        // the gate, and a second hello would spend the fresh nonce for no gain --
        // and a hello against a spent nonce is REFUSED, which tears down the
        // authorisation that just succeeded.
        if (authorized) {
            lastAuthTrace = "already authorised; second hello suppressed"
            return@withLock true
        }
        authenticateLocked(identity)
    }

    /**
     * The v2 hello:
     *
     *     {"op":"hello","version":2,"host_id":H,"host_name":N,"client_nonce":C,"response":R}
     *
     * R = HMAC(secret, "X4AUTH2|host|D|C|H|I"). The reader answers with
     * reader_proof = HMAC(secret, "X4AUTH2|reader|D|C|H|I") over the same,
     * pre-rotation D. Authorised only when that proof verifies in constant time,
     * for the device_id the pairing stored.
     */
    private suspend fun authenticateLocked(identity: HostIdentity): Boolean {
        val trace = StringBuilder()
        fun note(s: String) { trace.append(s).append('\n'); lastAuthTrace = trace.toString().trim() }
        note("host=${identity.hostId.take(8)}… reader=${identity.deviceId?.take(8) ?: "?"}")
        authorized = false
        authedDeviceId = null
        lastAuthFailure = null
        // Always a fresh GATT read, never the cached status: the reader rotates
        // device_nonce on every accepted hello, and notifications can shed it.
        val pre = runCatching { readStatus() }.getOrElse {
            note("pre-read FAILED: ${it.message}")
            lastAuthFailure = "status read failed"
            return false
        }
        if ((pre.protocolVersion ?: 0) < PROTOCOL_VERSION) {
            note("protocol_version=${pre.protocolVersion ?: "-"}")
            lastAuthFailure = "old firmware"
            return false
        }
        val d = pre.deviceNonce
        val i = pre.deviceId
        note("nonce=${if (d.isNullOrBlank()) "ABSENT" else "ok(${d.length})"} device=${i?.take(8) ?: "ABSENT"}")
        if (d.isNullOrBlank() || i.isNullOrBlank()) {
            lastAuthFailure = "no nonce or device id"
            return false
        }
        if (identity.deviceId != null && identity.deviceId != i) {
            note("different reader")
            lastAuthFailure = "different reader"
            return false
        }
        val c = newClientNonce()
        val h = identity.hostId
        val macs = try {
            authMac(identity.secret, "host", d, c, h, i).toHex() to
                authMac(identity.secret, "reader", d, c, h, i)
        } catch (e: IllegalArgumentException) {
            note("bad stored secret")
            return false
        }
        val response = macs.first
        val expected = macs.second
        val command = JSONObject()
            .put("op", "hello")
            .put("version", PROTOCOL_VERSION)
            .put("host_id", h)
            .put("host_name", identity.hostName)
            .put("client_nonce", c)
            .put("response", response)
        // Ring the doorbell, then read: a notification is capped at ATT_MTU-3 and
        // can shed fields, so the proof is taken from whichever one carries it.
        val awaited = runCatching {
            commandAwait(command, 5_000) { it.readerProof != null || it.authError != null || it.state == "error" }
        }
        note("hello await=" + awaited.fold(
            { "proof=${it.readerProof != null} auth_error=${it.authError ?: "-"}" },
            { "timeout/err: ${it.message}" },
        ))
        val verdict = runCatching { readStatus() }.getOrNull()
        note("verdict proof=${verdict?.readerProof != null} auth_error=${verdict?.authError ?: verdict?.error ?: "-"}")
        val proofOk = listOfNotNull(awaited.getOrNull()?.readerProof, verdict?.readerProof)
            .any { proofMatches(expected, it) }
        val verdictId = verdict?.deviceId
        val sameReader = verdictId == null || verdictId == i
        val ok = proofOk && sameReader
        authedDeviceId = if (ok) i else null
        authorized = ok
        lastAuthFailure = when {
            ok -> null
            // The reader's own word wins: "unknown trusted host" means it forgot us.
            else -> awaited.getOrNull()?.authError ?: verdict?.authError
                ?: verdict?.error?.takeIf { verdict?.state == "error" && isAuthError(it) }
                ?: if (!sameReader) "different reader"
                else if (awaited.isFailure && verdict?.readerProof == null) "no reply"
                else "bad reader proof"
        }
        note("RESULT authorized=$ok (proof=$proofOk sameReader=$sameReader)")
        return ok
    }

    /**
     * Removes a book from the reader's card.
     *
     * The offline shelf is a two-way mirror: what is saved is on the reader, and
     * what is removed is not. Nothing is lost by deleting -- reading position
     * lives in kosync, so re-saving the book brings the position back with it.
     *
     * The reader treats "already absent" as success, so this is safe to call for
     * a book that was never pushed.
     */
    suspend fun deleteBook(filename: String, closeIfOpen: Boolean = false): Boolean {
        val command = JSONObject().put("op", "delete_book").put("name", filename)
        // The reader leaves the book first (saving its position), then deletes; that
        // takes a screen change, so the wait is longer.
        if (closeIfOpen) command.put("close", true)
        val result = runCatching {
            commandAwait(command, if (closeIfOpen) 15_000 else 8_000) { it.state == "saved" || it.state == "error" }
        }.getOrNull()
        return result?.state == "saved"
    }

    // -------------------------------------------------------------- upload

    data class UploadResult(
        val bytes: Long,
        val elapsedMs: Long,
        val finalState: String?,
        /** True once the reader reports it saved us as a trusted host. */
        val pairedNow: Boolean,
        /** The reader applied the `position` sent with a book, before showing it. */
        val positionApplied: Boolean = false,
    )

    /**
     * Streams [file] to the reader as [kind] under [name].
     *
     * [onProgress] is called with bytes the reader has *acknowledged* where a
     * credit checkpoint is available, and with bytes handed to the stack in
     * between, so a 5 MB EPUB over BLE does not look frozen.
     */
    suspend fun upload(
        file: File,
        name: String,
        kind: String = "book",
        req: Int? = null,
        /**
         * Overwrite a book the reader already holds. Only for a Calibre update:
         * an ordinary send relies on "exists" to learn a book is already there.
         */
        replace: Boolean = false,
        /** Build stamp for a firmware image (yyyyMMdd.HHmm). Required for firmware. */
        version: String? = null,
        /** firmware.json `signature` (hex). Required for firmware; the reader verifies it. */
        signature: String? = null,
        /**
         * `book` only: a reading position the reader applies at commit, before
         * the book appears. The same fields as a `progress` batch entry, minus
         * `filename`. Null omits the field. Send only to a reader whose `about`
         * document lists `book_position` in `features`; an invalid one fails the
         * whole start_put with "invalid position".
         */
        position: JSONObject? = null,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadResult {
        val total = file.length()
        if (total <= 0L) throw BleException("\"$name\" is empty")
        if (kind == "firmware" && (version.isNullOrBlank() || signature.isNullOrBlank())) {
            throw BleException("Unsigned firmware")
        }
        return uploadCore(
            name = name,
            kind = kind,
            req = req,
            total = total,
            sha = sha256Hex(file),
            open = { file.inputStream() },
            onProgress = onProgress,
            replace = replace,
            version = version,
            signature = signature,
            position = position?.takeIf { kind == "book" },
        )
    }

    /**
     * Uploads an in-memory payload — the `CPCT` catalogue container, which is
     * built rather than read and is a few kilobytes at most.
     *
     * [name] is null for the catalogue kinds because the reader ignores it: a
     * `catalog_page` / `catalog_detail` is staged at a fixed scratch path,
     * parsed on commit and deleted, so there is nothing to name.
     */
    suspend fun uploadBytes(
        data: ByteArray,
        kind: String,
        req: Int? = null,
        name: String? = null,
        commitDone: (DeviceStatus) -> Boolean = DEFAULT_COMMIT_DONE,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadResult {
        if (data.isEmpty()) throw BleException("Nothing to upload")
        return uploadCore(
            name = name,
            kind = kind,
            req = req,
            total = data.size.toLong(),
            sha = sha256Hex(data),
            open = { java.io.ByteArrayInputStream(data) },
            onProgress = onProgress,
            commitDone = commitDone,
        )
    }

    /**
     * The one upload path: `start_put`, framed writes on `data-in`, credit
     * flow control, `commit`.
     *
     * [req] is the Store's only addition to it. It is present exactly when this
     * upload answers a `pending` request the reader published, and the reader
     * refuses an answer naming any other id with `stale request` — which is
     * what stops a slow reply, arriving after the user has paged on, from
     * repainting the screen with the page they left. An ordinary transfer omits
     * the field entirely, which is what the reader reads as "not an answer".
     */
    private suspend fun uploadCore(
        name: String?,
        kind: String,
        req: Int?,
        total: Long,
        sha: String,
        open: () -> java.io.InputStream,
        onProgress: (sent: Long, total: Long) -> Unit,
        commitDone: (DeviceStatus) -> Boolean = DEFAULT_COMMIT_DONE,
        replace: Boolean = false,
        version: String? = null,
        signature: String? = null,
        position: JSONObject? = null,
    ): UploadResult = transferLock.withLock {
        if (!authorized) throw BleException("Not authorised", reason = Reason.AUTH)
        val dataInChar = dataIn ?: throw BleException("Not connected")
        if (name != null && !isSafeTransferName(name)) {
            throw BleException("\"$name\" is not a name the reader will accept")
        }

        // Android keeps a link at its balanced 30-50 ms interval unless the app asks,
        // whatever the reader requested; every frame waits on that interval.
        requestFastLink()

        // mtu - 3 is the ATT payload; the frame header eats 4 more.
        val chunk = minOf(MAX_CHUNK_BYTES, maxOf(1, mtu - 3 - FRAME_HEADER_BYTES))
        val started = System.currentTimeMillis()

        val startPut = JSONObject()
            .put("op", "start_put")
            .put("kind", kind)
            .put("size", total)
            .put("sha256", sha)
            .put("resume", false)
            .put("chunk_size", chunk)
            .put("ack_bytes", ACK_BYTES)
        if (name != null) startPut.put("name", name)
        if (req != null) startPut.put("req", req)
        // Omitted rather than false when not replacing, so older firmware sees
        // exactly the request it always has.
        if (replace) startPut.put("replace", true)
        if (version != null) startPut.put("version", version)
        if (signature != null) startPut.put("signature", signature)
        if (position != null) startPut.put("position", position)

        try {
            val ready = commandAwait(startPut, 15_000) {
                it.state == "receiving" || it.state == "error"
            }
            failIfError(ready)

            var sequence = 0L
            var sent = 0L
            var lastCredit = 0L
            val buffer = ByteArray(chunk)

            open().use { input ->
                while (sent < total) {
                    // Must be a *full* read: the reader was told chunk_size in
                    // start_put and treats a short frame as a protocol error,
                    // and InputStream.read is allowed to return fewer bytes
                    // than asked for even mid-file.
                    val n = input.readFully(buffer)
                    if (n <= 0) break
                    val frame = ByteArray(FRAME_HEADER_BYTES + n)
                    ByteBuffer.wrap(frame, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(sequence.toInt())
                    System.arraycopy(buffer, 0, frame, FRAME_HEADER_BYTES, n)
                    writeChar(dataInChar, frame, withoutResponse = true)
                    sequence += 1
                    sent += n
                    onProgress(sent, total)

                    if (sent - lastCredit >= ACK_BYTES) {
                        lastCredit = sent
                        awaitReceived(lastCredit)
                        onProgress(sent, total)
                    }
                }
            }
            if (sent != total) throw BleException("Read $sent of $total bytes from local storage")

            awaitReceived(total)

            val committed = commandAwait(
                JSONObject().put("op", "commit"), COMMIT_TIMEOUT_MS, commitDone,
            )
            failIfError(committed)

            return UploadResult(
                bytes = total,
                elapsedMs = System.currentTimeMillis() - started,
                finalState = committed.state,
                pairedNow = committed.paired,
                positionApplied = position != null && readPositionApplied(committed),
            )
        } catch (e: Throwable) {
            // Best effort: if the link is already gone this just fails again,
            // and the reader drops its own partial ".ble-" staging file.
            runCatching { writeControl(JSONObject().put("op", "cancel")) }
            throw e
        }
    }

    /**
     * `position_applied` after a book commit that carried a `position`.
     * Notifications shed fields to fit, so a flag missing from the committed
     * notification is confirmed with a status READ before it counts as false.
     */
    private suspend fun readPositionApplied(committed: DeviceStatus): Boolean {
        fun flag(s: DeviceStatus?): Boolean = s != null && runCatching {
            JSONObject(s.raw).optBoolean("position_applied", false)
        }.getOrDefault(false)
        if (flag(committed)) return true
        return flag(runCatching { readStatus() }.getOrNull())
    }

    /**
     * Declines a Store request we already know we cannot answer.
     *
     * Without this the reader sits out the whole 20 s (or 45 s) window before
     * showing "The phone did not answer". [message] is truncated to 96 bytes by
     * the firmware and shown to the user verbatim, so it should read as a
     * reason and not as a stack trace. The reader ignores a `catalog_error`
     * naming anything but the outstanding request, so this is safe to call
     * late; it is not safe to call after we have already sent `start_put`.
     */
    suspend fun sendCatalogError(req: Int, message: String) {
        writeControl(
            JSONObject()
                .put("op", "catalog_error")
                .put("req", req)
                .put("error", CatalogContainer.clampUtf8(message, 96))
        )
    }

    /** Fills [buffer] unless EOF intervenes; returns the byte count. */
    private fun java.io.InputStream.readFully(buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val n = read(buffer, filled, buffer.size - filled)
            if (n <= 0) break
            filled += n
        }
        return filled
    }

    private suspend fun awaitReceived(target: Long) {
        val s = awaitStatus(60_000) {
            it.state == "error" || (it.received != null && it.received >= target)
        }
        failIfError(s)
    }

    // ------------------------------------------------------------ download

    /**
     * Pulls a download kind (the firmware advertises `crash_report`) frame by
     * frame, acknowledging each one. Frames must arrive in sequence; a gap
     * means we lost a notification and the transfer is not trustworthy.
     */
    suspend fun download(
        kind: String,
        onProgress: (received: Long) -> Unit = {},
    ): ByteArray = transferLock.withLock {
        if (!authorized) throw BleException("Not authorised", reason = Reason.AUTH)
        val chunks = mutableListOf<ByteArray>()
        var received = 0L
        var expected = 0L

        val start = JSONObject()
            .put("op", "start_get")
            .put("kind", kind)
            .put("chunk_size", DOWNLOAD_CHUNK_BYTES)

        coroutineScope {
            // Buffer frames from the moment before start_get is written.
            // Subscribing lazily inside the loop would drop any frame that
            // lands between two iterations.
            val queue = Channel<DataFrame>(Channel.UNLIMITED)
            val subscribed = CompletableDeferred<Unit>()
            val pump = launch {
                dataOutFrames
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { queue.trySend(it) }
            }
            subscribed.await()

            try {
                val ready = commandAwait(start, 15_000) {
                    it.state == "sending" || it.state == "sent" || it.state == "error"
                }
                failIfError(ready)

                // Frames can still be in flight after "sent" arrives, so drain
                // until the byte counts agree rather than trusting the state.
                var draining = true
                while (draining) {
                    val done = _status.value
                    if (done?.state == "error") failIfError(done)
                    if (done?.state == "sent" && done.size != null && received >= done.size) break

                    val frame = withTimeoutOrNull(20_000) { queue.receive() }
                    if (frame == null) {
                        if (_status.value?.state == "sent") { draining = false; continue }
                        throw BleException("Reader stopped sending mid-download")
                    }
                    if (frame.sequence != expected) {
                        throw BleException(
                            "Out-of-order frame: got ${frame.sequence}, expected $expected"
                        )
                    }
                    chunks += frame.payload
                    received += frame.payload.size
                    expected += 1
                    onProgress(received)
                    writeControl(JSONObject().put("op", "get_ack").put("sequence", frame.sequence))
                }
            } catch (e: Throwable) {
                runCatching { writeControl(JSONObject().put("op", "cancel")) }
                throw e
            } finally {
                pump.cancel()
                queue.close()
            }
        }

        val out = ByteArray(received.toInt())
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, offset, c.size)
            offset += c.size
        }
        return out
    }

    // -------------------------------------------------------------- errors

    /** Reader error codes are terse; these are the ones a user can act on. */
    private fun friendlyError(code: String?): String = when (code) {
        null -> "The reader rejected the operation"
        "exists" -> "That file is already on the reader"
        "pairing window closed" -> "On the reader, open Settings"
        "invalid pair request" -> "The reader refused the pairing"
        "invalid trusted host auth", "unknown trusted host" ->
            "The reader does not know this phone. Pair again."
        "signature required" -> "Unsigned firmware"
        "unsafe book filename" ->
            "The reader rejected the filename. Letters, numbers, dots, dashes and underscores only."
        "invalid book size" -> "The reader rejected the file size"
        "sha256 mismatch" -> "The file arrived corrupted (checksum mismatch) — try again"
        "size mismatch" -> "The reader received the wrong number of bytes — try again"
        "could not create books directory" -> "The reader could not write to /Books"
        "unsupported transfer kind" -> "This reader firmware cannot accept that kind of file"
        // Store codes. "stale request" is a normal race, not a fault: the user
        // paged on before our answer arrived, and the reader has already asked
        // a newer question that we will answer instead.
        "stale request" -> "The reader had already moved on from that request"
        "unexpected book" -> "The reader was not expecting that book"
        "store not open" -> "The reader is no longer on its Store screen"
        "empty catalog page" -> "Calibre returned no books for that page"
        "not a catalog page", "catalog json", "catalog header size", "catalog item" ->
            "The reader could not read the catalogue page this app built"
        "catalog truncated" -> "The catalogue page arrived incomplete — try again"
        "cover too large" -> "A cover was too large for the reader"
        "invalid catalog size" -> "The catalogue page was too large for the reader"
        "transfer too large" -> "The reader does not have room for this file"
        else -> "Reader error: $code"
    }
}

enum class BleConnection { IDLE, SCANNING, CONNECTING, BONDING, CONNECTED }
