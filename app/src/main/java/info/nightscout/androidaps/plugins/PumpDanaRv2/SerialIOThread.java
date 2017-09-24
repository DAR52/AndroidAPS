package info.nightscout.androidaps.plugins.PumpDanaRv2;

import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.plugins.PumpDanaR.DanaRPump;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.MessageBase;
import info.nightscout.androidaps.plugins.PumpDanaRv2.comm.MessageHashTable_v2;
import info.nightscout.utils.CRC;

/**
 * Created by mike on 17.07.2016.
 */
public class SerialIOThread extends Thread {
    private static Logger log = LoggerFactory.getLogger(SerialIOThread.class);

    private InputStream mInputStream = null;
    private OutputStream mOutputStream = null;
    private BluetoothSocket mRfCommSocket;

    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> scheduledDisconnection = null;

    private boolean mKeepRunning = true;
    private byte[] mReadBuff = new byte[0];

    MessageBase processedMessage;

    public SerialIOThread(BluetoothSocket rfcommSocket) {
        super(SerialIOThread.class.toString());

        mRfCommSocket = rfcommSocket;
        try {
            mOutputStream = mRfCommSocket.getOutputStream();
            mInputStream = mRfCommSocket.getInputStream();
        } catch (IOException e) {
            log.error("Unhandled exception", e);
        }
        this.start();
    }

    @Override
    public final void run() {
        try {
            while (mKeepRunning) {
                int availableBytes = mInputStream.available();
                // Ask for 1024 byte (or more if available)
                byte[] newData = new byte[Math.max(1024, availableBytes)];
                int gotBytes = mInputStream.read(newData);
                // When we are here there is some new data available
                appendToBuffer(newData, gotBytes);

                // process all messages we already got
                while (mReadBuff.length > 3) { // 3rd byte is packet size. continue only if we an determine packet size
                    byte[] extractedBuff = cutMessageFromBuffer();
                    if (extractedBuff == null) break; // message is not complete in buffer (wrong packet calls disconnection)

                    int command = (extractedBuff[5] & 0xFF) | ((extractedBuff[4] << 8) & 0xFF00);

                    MessageBase message;
                    if (processedMessage != null && processedMessage.getCommand() == command) {
                        message = processedMessage;
                    } else {
                        // get it from hash table
                        message = MessageHashTable_v2.findMessage(command);
                    }

                    if (Config.logDanaMessageDetail)
                        log.debug("<<<<< " + message.getMessageName() + " " + message.toHexString(extractedBuff));

                    // process the message content
                    message.received = true;
                    message.handleMessage(extractedBuff);
                    synchronized (message) {
                        message.notify();
                    }
                    scheduleDisconnection();
                }
            }
        } catch (Exception e) {
            if (Config.logDanaSerialEngine && e.getMessage().indexOf("bt socket closed") < 0)
                log.error("Thread exception: ", e);
            mKeepRunning = false;
        }
        disconnect("EndOfLoop");
    }

    void appendToBuffer(byte[] newData, int gotBytes) {
        // add newData to mReadBuff
        byte[] newReadBuff = new byte[mReadBuff.length + gotBytes];
        System.arraycopy(mReadBuff, 0, newReadBuff, 0, mReadBuff.length);
        System.arraycopy(newData, 0, newReadBuff, mReadBuff.length, gotBytes);
        mReadBuff = newReadBuff;
    }

    byte[] cutMessageFromBuffer() {
        if (mReadBuff[0] == (byte) 0x7E && mReadBuff[1] == (byte) 0x7E) {
            int length = (mReadBuff[2] & 0xFF) + 7;
            // Check if we have enough data
            if (mReadBuff.length < length) {
                return null;
            }
            if (mReadBuff[length - 2] != (byte) 0x2E || mReadBuff[length - 1] != (byte) 0x2E) {
                log.error("wrong packet lenght=" + length + " data " + MessageBase.toHexString(mReadBuff));
                disconnect("wrong packet");
                return null;
            }

            short crc = CRC.getCrc16(mReadBuff, 3, length - 7);
            byte crcByte0 = (byte) (crc >> 8 & 0xFF);
            byte crcByte1 = (byte) (crc & 0xFF);

            byte crcByte0received = mReadBuff[length - 4];
            byte crcByte1received = mReadBuff[length - 3];

            if (crcByte0 != crcByte0received || crcByte1 != crcByte1received) {
                log.error("CRC Error" + String.format("%02x ", crcByte0) + String.format("%02x ", crcByte1) + String.format("%02x ", crcByte0received) + String.format("%02x ", crcByte1received));
                disconnect("crc error");
                return null;
            }
            // Packet is verified here. extract data
            byte[] extractedBuff = new byte[length];
            System.arraycopy(mReadBuff, 0, extractedBuff, 0, length);
            // remove extracted data from read buffer
            byte[] unprocessedData = new byte[mReadBuff.length - length];
            System.arraycopy(mReadBuff, length, unprocessedData, 0, unprocessedData.length);
            mReadBuff = unprocessedData;
            return extractedBuff;
        } else {
            log.error("Wrong beginning of packet len=" + mReadBuff.length + "    " + MessageBase.toHexString(mReadBuff));
            disconnect("Wrong beginning of packet");
            return null;
        }
    }

    public synchronized void sendMessage(MessageBase message) {
        if (!mRfCommSocket.isConnected()) {
            log.error("Socket not connected on sendMessage");
            return;
        }
        processedMessage = message;

        byte[] messageBytes = message.getRawMessageBytes();
        if (Config.logDanaSerialEngine)
            log.debug(">>>>> " + message.getMessageName() + " " + message.toHexString(messageBytes));

        try {
            mOutputStream.write(messageBytes);
        } catch (Exception e) {
            log.error("sendMessage write exception: ", e);
        }

        synchronized (message) {
            try {
                message.wait(5000);
            } catch (InterruptedException e) {
                log.error("sendMessage InterruptedException", e);
            }
        }

        SystemClock.sleep(200);
        if (!message.received) {
            log.warn("Reply not received " + message.getMessageName());
            if (message.getCommand() == 0xF0F1) {
                DanaRPump.getInstance().isNewPump = false;
                log.debug("Old firmware detected");
            }
        }
        scheduleDisconnection();
    }

    public void scheduleDisconnection() {
        class DisconnectRunnable implements Runnable {
            public void run() {
                disconnect("scheduleDisconnection");
                scheduledDisconnection = null;
            }
        }
        // prepare task for execution in 5 sec
        // cancel waiting task to prevent sending multiple disconnections
        if (scheduledDisconnection != null)
            scheduledDisconnection.cancel(false);
        Runnable task = new DisconnectRunnable();
        final int sec = 5;
        scheduledDisconnection = worker.schedule(task, sec, TimeUnit.SECONDS);
    }

    public void disconnect(String reason) {
        mKeepRunning = false;
        try {
            mInputStream.close();
        } catch (Exception e) {
            if (Config.logDanaSerialEngine) log.debug(e.getMessage());
        }
        try {
            mOutputStream.close();
        } catch (Exception e) {
            if (Config.logDanaSerialEngine) log.debug(e.getMessage());
        }
        try {
            mRfCommSocket.close();
        } catch (Exception e) {
            if (Config.logDanaSerialEngine) log.debug(e.getMessage());
        }
        try {
            System.runFinalization();
        } catch (Exception e) {
            if (Config.logDanaSerialEngine) log.debug(e.getMessage());
        }
        if (Config.logDanaSerialEngine) log.debug("Disconnected: " + reason);
    }

}
