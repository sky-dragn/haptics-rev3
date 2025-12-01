package xyz.dragn.hapticsrev3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import xyz.dragn.hapticsrev3.ui.theme.HapticsRev3Theme
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

const val ACTION_USB_PERMISSION = "xyz.dragn.hapticsrev3.USB_PERMISSION"

const val PORT = 9001

class MainActivity : ComponentActivity() {
    val statuses = HashMap<String, String>()
    fun setStatus(who: String, text: String) {
        synchronized(statuses) {
            statuses.put(who, text)
        }
        runOnUiThread {
            setContent {
                HapticsRev3Theme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Text(
                            text = "$statuses",
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
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

    fun toast(x: String) {
        log(x)
        runOnUiThread {
            Toast.makeText(this, x, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        log("onCreate")

        setStatus("serial", "uninit")
        setStatus("network", "uninit")

        // create threads if they are dead
        try {
            if (threadNetworkInst == null) {
                threadNetworkInst = Thread { threadNetwork() }
                threadNetworkInst!!.start()
            }
            if (threadSerialInst == null) {
                threadSerialInst = Thread { threadSerial() }
                threadSerialInst!!.start()
            }
        } catch (e: Exception) {
            log("error creating threads: $e")
        }

        // open serial if we can
        openSerial()
    }

    var usbReceiver: UsbReceiver? = null
    override fun onResume() {
        super.onResume()
        usbReceiver = UsbReceiver(this)
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(usbReceiver)
    }

    override fun onNewIntent(intent: Intent?) {
        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            super.onNewIntent(intent)
        }
        log("detected")
        openSerial()
    }

    val pokeys = ByteArray(32)

    var serial: UsbSerialPort? = null

    fun openSerial() {
        // don't re-open
        if (serial?.isOpen == true) {
            log("already open")
            return
        }
        setStatus("serial", "opening")

        // Find all available drivers from attached devices.
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            toast("No serial devices")
            setStatus("serial", "closed")
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
            toast("Serial device has no ports")
            setStatus("serial", "closed")
            return
        }
        val x = driver.ports[0] // Most devices have just one port (port 0)
        x.open(connection)
        x.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        x.write(byteArrayOf(0x00), 0)
        serial = x

        setStatus("serial", "open")
    }

    var threadNetworkInst: Thread? = null
    fun threadNetwork() {
        setStatus("network", "opening")
        val sock = try {
            DatagramSocket(PORT)
        } catch (e: Exception) {
            setStatus("network", "failed")
            toast("error opening socket: $e")
            return
        }
        sock.soTimeout = 2000
        setStatus("network", "open on $PORT")

        while (true) {
            try {
                val pkt = DatagramPacket(ByteArray(256), 256)
                sock.receive(pkt)
                val addrlen = pkt.data.indexOf(0)
                val addr = pkt.data.decodeToString(0, addrlen)
                val addrend = (addrlen + 4) / 4 * 4
                if (!addr.startsWith("/avatar/parameters/SkyHaptics_")) {
                    continue
                }
                if (pkt.data.slice(addrend until addrend + 4) != listOf(
                        ','.code.toByte(),
                        'f'.code.toByte(),
                        0,
                        0
                    )
                ) {
                    continue
                }
                val setpoint = ByteBuffer.allocate(4).put(pkt.data, addrend + 4, 4).getFloat(0)

                log("ahahaha $addr $setpoint")
                val idx = addr.split("_")[1].toInt()
                synchronized(pokeys) {
                    pokeys[idx] = (setpoint * 255.0f).toInt().toByte()
                }
//                log("got udp lmao length ${pkt.length}")
            } catch (_: SocketTimeoutException) {
                if (threadNetworkInst!!.isInterrupted) return
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                toast("error in network thread $e")
            }
        }
    }

    var threadSerialInst: Thread? = null
    fun threadSerial() {
        var count = 0
        while (true) {
            count = (count + 1) % 100
            if (count == 0) {
                log("stored: " + pokeys.toList().toString())
            }
            if (serial != null) {
                try {
                    if (serial?.isOpen != true) {
                        throw IOException()
                    }
                    val send = synchronized(pokeys) { byteArrayOf(0x01) + pokeys }

                    if (count == 0) {
                        log("sent: " + send.toList().toString())
                    }
                    runOnUiThread {
                        serial!!.write(send, 10)
                    }
                } catch (_: IOException) {
                    toast("IO error, closing port")
                    setStatus("serial", "closed")
                    serial = null
                } catch (e: Exception) {
                    toast("error in serial thread $e")
                }
            }
            Thread.sleep(10)
        }
    }

    class UsbReceiver(val act: MainActivity) : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            act.log("received broadcast")
        }

    }
}
