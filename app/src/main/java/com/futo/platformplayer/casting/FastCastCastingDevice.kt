package com.futo.platformplayer.casting

import android.os.Looper
import android.util.Log
import com.futo.platformplayer.casting.models.*
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.getConnectedSocket
import com.futo.platformplayer.models.CastingDeviceInfo
import com.futo.platformplayer.toHexString
import com.futo.platformplayer.toInetAddress
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

enum class Opcode(val value: Byte) {
    NONE(0),
    PLAY(1),
    PAUSE(2),
    RESUME(3),
    STOP(4),
    SEEK(5),
    PLAYBACK_UPDATE(6),
    VOLUME_UPDATE(7),
    SET_VOLUME(8)
}

class FastCastCastingDevice : CastingDevice {
    //See for more info: TODO

    override val protocol: CastProtocolType get() = CastProtocolType.FASTCAST;
    override val isReady: Boolean get() = name != null && addresses != null && addresses?.isNotEmpty() == true && port != 0;
    override var usedRemoteAddress: InetAddress? = null;
    override var localAddress: InetAddress? = null;
    override val canSetVolume: Boolean get() = true;

    var addresses: Array<InetAddress>? = null;
    var port: Int = 0;

    private var _socket: Socket? = null;
    private var _outputStream: DataOutputStream? = null;
    private var _inputStream: DataInputStream? = null;
    private var _scopeIO: CoroutineScope? = null;
    private var _started: Boolean = false;

    constructor(name: String, addresses: Array<InetAddress>, port: Int) : super() {
        this.name = name;
        this.addresses = addresses;
        this.port = port;
    }

    constructor(deviceInfo: CastingDeviceInfo) : super() {
        this.name = deviceInfo.name;
        this.addresses = deviceInfo.addresses.map { a -> a.toInetAddress() }.filterNotNull().toTypedArray();
        this.port = deviceInfo.port;
    }

    override fun getAddresses(): List<InetAddress> {
        return addresses?.toList() ?: listOf();
    }

    override fun loadVideo(streamType: String, contentType: String, contentId: String, resumePosition: Double, duration: Double) {
        if (invokeInIOScopeIfRequired({ loadVideo(streamType, contentType, contentId, resumePosition, duration) })) {
            return;
        }

        Logger.i(TAG, "Start streaming (streamType: $streamType, contentType: $contentType, contentId: $contentId, resumePosition: $resumePosition, duration: $duration)");

        time = resumePosition;
        sendMessage(Opcode.PLAY, FastCastPlayMessage(
            container = contentType,
            url = contentId,
            time = resumePosition.toInt()
        ));
    }

    override fun loadContent(contentType: String, content: String, resumePosition: Double, duration: Double) {
        if (invokeInIOScopeIfRequired({ loadContent(contentType, content, resumePosition, duration) })) {
            return;
        }

        Logger.i(TAG, "Start streaming content (contentType: $contentType, resumePosition: $resumePosition, duration: $duration)");

        time = resumePosition;
        sendMessage(Opcode.PLAY, FastCastPlayMessage(
            container = contentType,
            content = content,
            time = resumePosition.toInt()
        ));
    }

    override fun changeVolume(volume: Double) {
        if (invokeInIOScopeIfRequired({ changeVolume(volume) })) {
            return;
        }

        this.volume = volume
        sendMessage(Opcode.SET_VOLUME, FastCastSetVolumeMessage(volume))
    }

    override fun seekVideo(timeSeconds: Double) {
        if (invokeInIOScopeIfRequired({ seekVideo(timeSeconds) })) {
            return;
        }

        sendMessage(Opcode.SEEK, FastCastSeekMessage(
            time = timeSeconds.toInt()
        ));
    }

    override fun resumeVideo() {
        if (invokeInIOScopeIfRequired(::resumeVideo)) {
            return;
        }

        sendMessage(Opcode.RESUME);
    }

    override fun pauseVideo() {
        if (invokeInIOScopeIfRequired(::pauseVideo)) {
            return;
        }

        sendMessage(Opcode.PAUSE);
    }

    override fun stopVideo() {
        if (invokeInIOScopeIfRequired(::stopVideo)) {
            return;
        }

        sendMessage(Opcode.STOP);
    }

    private fun invokeInIOScopeIfRequired(action: () -> Unit): Boolean {
        if(Looper.getMainLooper().thread == Thread.currentThread()) {
            _scopeIO?.launch { action(); }
            return true;
        }

        return false;
    }

    override fun stopCasting() {
        if (invokeInIOScopeIfRequired(::stopCasting)) {
            return;
        }

        stopVideo();

        Logger.i(TAG, "Stopping active device because stopCasting was called.")
        stop();
    }

    override fun start() {
        val adrs = addresses ?: return;
        if (_started) {
            return;
        }

        _started = true;
        Logger.i(TAG, "Starting...");

        _scopeIO?.cancel();
        Logger.i(TAG, "Cancelled previous scopeIO because a new one is starting.")
        _scopeIO = CoroutineScope(Dispatchers.IO);

        Thread {
            connectionState = CastConnectionState.CONNECTING;

            while (_scopeIO?.isActive == true) {
                try {
                    val connectedSocket = getConnectedSocket(adrs.toList(), port);
                    if (connectedSocket == null) {
                        Thread.sleep(3000);
                        continue;
                    }

                    usedRemoteAddress = connectedSocket.inetAddress;
                    localAddress = connectedSocket.localAddress;
                    connectedSocket.close();
                    break;
                } catch (e: Throwable) {
                    Logger.w(ChromecastCastingDevice.TAG, "Failed to get setup initial connection to FastCast device.", e)
                }
            }

            //Connection loop
            while (_scopeIO?.isActive == true) {
                Logger.i(TAG, "Connecting to FastCast.");
                connectionState = CastConnectionState.CONNECTING;

                try {
                    _socket = Socket(usedRemoteAddress, port);
                    Logger.i(TAG, "Successfully connected to FastCast at $usedRemoteAddress:$port");

                    try {
                        _outputStream = DataOutputStream(_socket?.outputStream);
                        _inputStream = DataInputStream(_socket?.inputStream);
                    } catch (e: Throwable) {
                        Logger.i(TAG, "Failed to authenticate to FastCast.", e);
                    }
                } catch (e: IOException) {
                    _socket?.close();
                    Logger.i(TAG, "Failed to connect to FastCast.", e);

                    connectionState = CastConnectionState.CONNECTING;
                    Thread.sleep(3000);
                    continue;
                }

                localAddress = _socket?.localAddress;
                connectionState = CastConnectionState.CONNECTED;

                val buffer = ByteArray(4096);

                Logger.i(TAG, "Started receiving.");
                while (_scopeIO?.isActive == true) {
                    try {
                        val inputStream = _inputStream ?: break;
                        Log.d(TAG, "Receiving next packet...");
                        val b1 = inputStream.readUnsignedByte();
                        val b2 = inputStream.readUnsignedByte();
                        val b3 = inputStream.readUnsignedByte();
                        val b4 = inputStream.readUnsignedByte();
                        val size = ((b4.toLong() shl 24) or (b3.toLong() shl 16) or (b2.toLong() shl 8) or b1.toLong()).toInt();
                        if (size > buffer.size) {
                            Logger.w(TAG, "Skipping packet that is too large $size bytes.")
                            inputStream.skip(size.toLong());
                            continue;
                        }

                        Log.d(TAG, "Received header indicating $size bytes. Waiting for message.");
                        inputStream.read(buffer, 0, size);

                        val messageBytes = buffer.sliceArray(IntRange(0, size));
                        Log.d(TAG, "Received $size bytes: ${messageBytes.toHexString()}.");

                        val opcode = messageBytes[0];
                        var json: String? = null;
                        if (size > 1) {
                            json = messageBytes.sliceArray(IntRange(1, size - 1)).decodeToString();
                        }

                        try {
                            handleMessage(Opcode.values().first { it.value == opcode }, json);
                        } catch (e:Throwable) {
                            Logger.w(TAG, "Failed to handle message.", e);
                        }
                    } catch (e: java.net.SocketException) {
                        Logger.e(TAG, "Socket exception while receiving.", e);
                        break;
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Exception while receiving.", e);
                        break;
                    }
                }
                _socket?.close();
                Logger.i(TAG, "Socket disconnected.");

                connectionState = CastConnectionState.CONNECTING;
                Thread.sleep(3000);
            }

            Logger.i(TAG, "Stopped connection loop.");
            connectionState = CastConnectionState.DISCONNECTED;
        }.start();

        Logger.i(TAG, "Started.");
    }

    private fun handleMessage(opcode: Opcode, json: String? = null) {
        when (opcode) {
            Opcode.PLAYBACK_UPDATE -> {
                if (json == null) {
                    Logger.w(TAG, "Got playback update without JSON, ignoring.");
                    return;
                }

                val playbackUpdate = Json.decodeFromString<FastCastPlaybackUpdateMessage>(json);
                time = playbackUpdate.time.toDouble();
                isPlaying = when (playbackUpdate.state) {
                    1 -> true
                    else -> false
                }
            }
            Opcode.VOLUME_UPDATE -> {
                if (json == null) {
                    Logger.w(TAG, "Got volume update without JSON, ignoring.");
                    return;
                }

                val volumeUpdate = Json.decodeFromString<FastCastVolumeUpdateMessage>(json);
                volume = volumeUpdate.volume;
            }
            else -> { }
        }
    }

    private fun sendMessage(opcode: Opcode) {
        try {
            val size = 1;
            val outputStream = _outputStream;
            if (outputStream == null) {
                Logger.w(TAG, "Failed to send $size bytes, output stream is null.");
                return;
            }

            val serializedSizeLE = ByteArray(4);
            serializedSizeLE[0] = (size and 0xff).toByte();
            serializedSizeLE[1] = (size shr 8 and 0xff).toByte();
            serializedSizeLE[2] = (size shr 16 and 0xff).toByte();
            serializedSizeLE[3] = (size shr 24 and 0xff).toByte();
            outputStream.write(serializedSizeLE);

            val opcodeBytes = ByteArray(1);
            opcodeBytes[0] = opcode.value;
            outputStream.write(opcodeBytes);

            Log.d(TAG, "Sent $size bytes.");
        } catch (e: Throwable) {
            Logger.i(TAG, "Failed to send message.", e);
        }
    }

    private inline fun <reified T> sendMessage(opcode: Opcode, message: T) {
        try {
            val data: ByteArray;
            var jsonString: String? = null;
            if (message != null) {
                jsonString = Json.encodeToString(message);
                data = jsonString.encodeToByteArray();
            } else {
                data = ByteArray(0);
            }

            val size = 1 + data.size;
            val outputStream = _outputStream;
            if (outputStream == null) {
                Logger.w(TAG, "Failed to send $size bytes, output stream is null.");
                return;
            }

            val serializedSizeLE = ByteArray(4);
            serializedSizeLE[0] = (size and 0xff).toByte();
            serializedSizeLE[1] = (size shr 8 and 0xff).toByte();
            serializedSizeLE[2] = (size shr 16 and 0xff).toByte();
            serializedSizeLE[3] = (size shr 24 and 0xff).toByte();
            outputStream.write(serializedSizeLE);

            val opcodeBytes = ByteArray(1);
            opcodeBytes[0] = opcode.value;
            outputStream.write(opcodeBytes);

            if (data.isNotEmpty()) {
                outputStream.write(data);
            }

            Log.d(TAG, "Sent $size bytes: '$jsonString'.");
        } catch (e: Throwable) {
            Logger.i(TAG, "Failed to send message.", e);
        }
    }

    override fun stop() {
        Logger.i(TAG, "Stopping...");
        usedRemoteAddress = null;
        localAddress = null;
        _started = false;

        val socket = _socket;
        val scopeIO = _scopeIO;

        if (scopeIO != null && socket != null) {
            Logger.i(TAG, "Cancelling scopeIO with open socket.")

            scopeIO.launch {
                socket.close();
                connectionState = CastConnectionState.DISCONNECTED;
                scopeIO.cancel();
                Logger.i(TAG, "Cancelled scopeIO with open socket.")
            }
        } else {
            scopeIO?.cancel();
            Logger.i(TAG, "Cancelled scopeIO without open socket.")
        }

        _scopeIO = null;
        _socket = null;
        _outputStream = null;
        _inputStream = null;
        connectionState = CastConnectionState.DISCONNECTED;
    }

    override fun getDeviceInfo(): CastingDeviceInfo {
        return CastingDeviceInfo(name!!, CastProtocolType.FASTCAST, addresses!!.filter { a -> a.hostAddress != null }.map { a -> a.hostAddress!!  }.toTypedArray(), port);
    }

    companion object {
        val TAG = "FastCastCastingDevice";
    }
}