package xyz.dragn.hapticsrev3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.illposed.osc.OSCMessageEvent
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.OSCPortIn
import xyz.dragn.hapticsrev3.ui.theme.HapticsRev3Theme
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.Timer
import java.util.TimerTask

const val ACTION_USB_PERMISSION = "xyz.dragn.hapticsrev3.USB_PERMISSION"

val POINT_MAP = mapOf(
    0 to 1,
    1 to 0,
    2 to 2,
    3 to 6
)

class MainActivity : ComponentActivity() {
    var text = "hello\n"

    fun updateText() {
        setContent {
            HapticsRev3Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Text(
                        text = text,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    fun log(x: String) {
        Log.println(Log.INFO, "dragon", x)
//        synchronized(text) {
//            text += x + "\n"
//            updateText()
//        }
    }

    val pokeys = ByteArray(32)

    var serial: UsbSerialPort? = null

    fun openSerial() {

        // Find all available drivers from attached devices.
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            log("no devices")
            return
        }


        // Open a connection to the first available driver.
        val driver = availableDrivers[0]
        log("device ${driver.device.manufacturerName} ${driver.device.deviceName}")
        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            // add UsbManager.requestPermission(driver.getDevice(), ..) handling here
            log("requesting permission")
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
            manager.requestPermission(driver.device, pi)
            return
        }

        if (driver.ports.isEmpty()) {
            log("no ports")
            return
        }
        serial = driver.ports[0] // Most devices have just one port (port 0)
        serial!!.open(connection)
        serial!!.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        log("serial port open!")
        serial!!.write(byteArrayOf(0x00), 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateText()
        registerReceiver(UsbReceiver(this), IntentFilter(ACTION_USB_PERMISSION), RECEIVER_EXPORTED)
        openSerial()

        Timer().schedule(object : TimerTask() {
            override fun run() {
                if (serial == null) {
//                    log("closed")
                } else {
//                    log("pat")
                    try {
//                        serial!!.write(byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1), 0)

                        synchronized(pokeys) {
                            serial!!.write(byteArrayOf(0x01) + pokeys, 0)
                        }
                    } catch(e: Exception) {
//                        log("oops")
                    }
                }
            }
        }, 0, 100)

        try {
//            val osc = OSCPortIn(InetSocketAddress(9001))
//
//            osc.dispatcher.addListener(OSCPatternAddressMessageSelector("/")) { ev ->
//                val idx = ev.message.address.split("C")[1].toInt()
//                log("got OSC on $idx: ${ev.message.arguments}")
//            }
//            osc.startListening()
//            log("started osc :3")

            Thread {
                val sock = DatagramSocket(9001)
                while (true) {
                    try {
                        val pkt = DatagramPacket(ByteArray(256), 256)
                        sock.receive(pkt)
                        val addrlen = pkt.data.indexOf(0)
                        val addr = pkt.data.decodeToString(0, addrlen)
                        val addrend = (addrlen + 4) / 4 * 4
                        if (!addr.startsWith("/avatar/parameters/SkyHaptics")) {
                            continue
                        }
                        if (pkt.data.slice(addrend until addrend + 4) != listOf(','.code.toByte(), 'f'.code.toByte(), 0, 0)) {
                            continue
                        }
                        val thingy = ByteBuffer.allocate(4).put(pkt.data, addrend + 4, 4).getFloat(0)

                        log("ahahaha $addr $thingy")
                        val idx = addr.split("C")[1].toInt()
                        synchronized(pokeys) {
                            pokeys[POINT_MAP[idx]!!] = (thingy * 255.0f).toInt().toByte()
                        }
//                        log("got udp lmao length ${pkt.length}")
                    } catch (e: Exception) {
                        log("asdf $e")
                    }
                }
            }.start()
        } catch (e: Exception) {
            log(":( $e")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            super.onNewIntent(intent)
        }
        log("detected")
        openSerial()
    }

    class UsbReceiver(val act: MainActivity) : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            act.log("received broadcast")
        }

    }
}
