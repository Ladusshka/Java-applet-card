package hwbsample;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.OwnerPIN;
import javacard.framework.Util;


public class HWBSample extends Applet {

    private static final byte CLA_OUR      = (byte) 0x80;
    private static final byte INS_GET_NAME = (byte) 0x00;
    private static final byte INS_STORE    = (byte) 0x02;
    private static final byte INS_RETRIEVE = (byte) 0x04;
    private static final byte INS_VERIFY   = (byte) 0x20;

  
    private static final short MAX_BYTES = 20;
    private static final byte  MAX_TRIES = 3;
    private static final byte  MAX_PIN_LEN = 16;
    private static final short SW_PIN_VERIFICATION_REQUIRED = (short) 0x6301;

    
    private final byte[] name = { 'B', 'O', 'B', 'R' };
    private final byte[] stored = new byte[(short)MAX_BYTES];
    private short storedLen = 0;
    private final OwnerPIN pin = new OwnerPIN(MAX_TRIES, MAX_PIN_LEN);

  
    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new HWBSample(bArray, bOffset, bLength);
    }

    
    protected HWBSample(byte[] bArray, short bOffset, byte bLength) {
        
        register(bArray, (short)(bOffset + 1), bArray[bOffset]);

        
        short idx = bOffset;

     
        byte iLen = bArray[idx];
        idx += (short)(1 + (iLen & 0xFF));

        
        byte cLen = bArray[idx];
        idx += (short)(1 + (cLen & 0xFF));

       
        if (idx < (short)(bOffset + bLength)) {
            byte aLen = bArray[idx++];
            if (aLen > 0) {
                byte useLen = aLen;
                if ((short)(useLen & 0xFF) > (short)MAX_PIN_LEN) {
                    useLen = MAX_PIN_LEN; 
                }
                pin.update(bArray, idx, useLen);
               
            }
        }
    }

    
    public boolean select() {
        return pin.getTriesRemaining() > 0;
    }


    public void deselect() {
        pin.reset();
    }

    public void process(APDU apdu) {
       
        if (selectingApplet()) {
            return;
        }

        byte[] buf = apdu.getBuffer();

        
        if (buf[ISO7816.OFFSET_CLA] != CLA_OUR) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        byte ins = buf[ISO7816.OFFSET_INS];
        switch (ins) {
            case INS_GET_NAME:
                handleGetName(apdu);
                return;

            case INS_VERIFY:
                handleVerify(apdu);
                return;

            case INS_STORE:
                ensurePinValidated();
                handleStore(apdu);
                return;

            case INS_RETRIEVE:
                ensurePinValidated();
                handleRetrieve(apdu);
                return;

            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    

    private void handleGetName(APDU apdu) {
        short le = apdu.setOutgoing();
        if (le > (short)name.length) {
            le = (short)name.length;
        }
        apdu.setOutgoingLength(le);
        apdu.sendBytesLong(name, (short)0, le);
    }

    private void handleVerify(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short lc = apdu.setIncomingAndReceive(); 

        if (lc <= 0 || lc > (short)MAX_PIN_LEN) {
            ISOException.throwIt((short)0x6300); 
        }

        boolean ok = pin.check(buf, ISO7816.OFFSET_CDATA, (byte)lc);
        if (!ok) {
            ISOException.throwIt((short)0x6300); 
        }
        
    }

    private void handleStore(APDU apdu) {
        byte[] buf = apdu.getBuffer();

        
        short total = 0;
        short got = apdu.setIncomingAndReceive();
        short off = ISO7816.OFFSET_CDATA;

        while (got > 0) {
            total += got;
            if (total > (short)MAX_BYTES) {
                ISOException.throwIt(ISO7816.SW_WRONG_LENGTH); 
            }
            got = apdu.receiveBytes((short)(off + total));
        }

        if (total > 0) {
            Util.arrayCopy(buf, ISO7816.OFFSET_CDATA, stored, (short)0, total);
        }
        storedLen = total; 
        
    }

    private void handleRetrieve(APDU apdu) {
   
        short ne = apdu.setOutgoing();

        
        if (ne < storedLen) {
            ISOException.throwIt((short)(ISO7816.SW_CORRECT_LENGTH_00 + (storedLen & 0x00FF)));
        }

        
        short toSend = storedLen;
        apdu.setOutgoingLength(toSend);
        if (toSend > 0) {
            apdu.sendBytesLong(stored, (short)0, toSend);
        }
        
    }

    private void ensurePinValidated() {
        if (!pin.isValidated()) {
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED); 
        }
    }
}
