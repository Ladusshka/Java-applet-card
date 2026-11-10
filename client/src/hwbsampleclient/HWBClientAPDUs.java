
/*
 * Copyright (c) 1998, 2025, Oracle and/or its affiliates. All rights reserved.
 */

package hwbsampleclient;



import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import com.oracle.javacard.ams.config.AID;

public class HWBClientAPDUs extends HWBSampleClient{

    static final CommandAPDU selectAppletAPDU = new CommandAPDU(0x00, 0xA4, 0x04, 0x00, AID.from(sAID_AppletInstance).toBytes(), 256);

    /**
     * Fill in this method with communication with the applet and processing responses.
     * @param channel allows sending APDUs and receives responses via .transmit method
     */
    @Override
    void interactWithApplet(CardChannel channel) throws CardException {
            System.out.println("Select:");
            printAPDU(selectAppletAPDU);
            ResponseAPDU responseSelect = channel.transmit(selectAppletAPDU);
            printAPDU(responseSelect);
            System.out.println();

           // 1) Jméno (INS=0x00), Le=4
            ResponseAPDU r = channel.transmit(new CommandAPDU(0x80, 0x00, 0x00, 0x00, 4));
            System.out.println("Name: " + new String(r.getData()));
            System.out.println("SW: " + Integer.toHexString(r.getSW()));
            System.out.println();

            // 2) VERIFY (INS=0x20) – MUSÍ odpovídat installParams z main()
            byte[] pin = new byte[] { '1','2','3','4' }; // ← pokud jste v main() zvolili ASCII "1234"
            // byte[] pin = new byte[] { 0x01,0x02,0x03,0x04 }; // ← pokud jste v main() zvolili 01 02 03 04
            r = channel.transmit(new CommandAPDU(0x80, 0x20, 0x00, 0x00, pin));
            System.out.println("VERIFY SW: " + Integer.toHexString(r.getSW()));
            if (r.getSW() != 0x9000) return; // neověřený PIN → nepokračovat

            // 3) STORE (INS=0x02) – max 20 B
            byte[] data = new byte[] { 0x01, 0x02, 0x03, 0x04 };
            r = channel.transmit(new CommandAPDU(0x80, 0x02, 0x00, 0x00, data));
            System.out.println("STORE SW: " + Integer.toHexString(r.getSW()));

            // 4) RETRIEVE (INS=0x04) – Le musí == uložené délce
            r = channel.transmit(new CommandAPDU(0x80, 0x04, 0x00, 0x00, data.length));
            System.out.println("GET  Data: " + java.util.Arrays.toString(r.getData()));
            System.out.println("GET  SW: " + Integer.toHexString(r.getSW()));



    }

    public static void main(String[] args) {
    // Installation parameters = initial PIN (must match the PIN you send in VERIFY)
    byte[] installParams = new byte[] { '1','2','3','4' }; // ASCII "1234"
    new HWBClientAPDUs().setupApplet(args, installParams);

    // Keep console open so you can read the output
    System.out.println("\n=== Finished. Press Enter to exit ===");
    try {
        new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
    } catch (java.io.IOException ignored) {}
}

}
