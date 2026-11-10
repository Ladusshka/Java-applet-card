/*
 * Copyright (c) 1998, 2025, Oracle and/or its affiliates. All rights reserved.
 */

package hwbsampleclient;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import com.oracle.javacard.ams.AMService;
import com.oracle.javacard.ams.AMServiceFactory;
import com.oracle.javacard.ams.AMSession;
import com.oracle.javacard.ams.config.CAPFile;
import com.oracle.javacard.ams.script.APDUScript;
import com.oracle.javacard.ams.script.ScriptFailedException;
import com.oracle.javacard.ams.script.Scriptable;

public abstract class HWBSampleClient {

	static final String isdAID = "aid:A000000151000000";
    // 0x11:0x22:0x33:0x44:0x55:0x00:0x01
    // 11223344550001
	static final String sAID_CAP            = "aid:112233445500";
	static final String sAID_AppletClass    = "aid:11223344550001";
	static final String sAID_AppletInstance = "aid:11223344550001";
	// static final String sAID_CAP            = "aid:a00000006203010C01";
	// static final String sAID_AppletClass    = "aid:a00000006203010C0101";
	// static final String sAID_AppletInstance = "aid:a00000006203010C0101";

    abstract void interactWithApplet(CardChannel channel) throws CardException;

    void setupApplet(String[] args, byte[] installParams) {
        boolean physical = false;
        if (args.length > 0 && args[0].equals("-physical")) {
            physical = true;
        }

		int iResult = 0;

		try {
            TestScript testScriptDeploy;
            TestScript testScriptUndeploy;

            if (!physical){
                CAPFile appFile = CAPFile.from(getArg(args, "cap"));
                Properties props = new Properties();
                props.load(new FileInputStream(getArg(args, "props")));

                // Create and configure Application Management Service
                AMService ams = AMServiceFactory.getInstance("GP2.2");
                ams.setProperties(props);
                for (String key : ams.getPropertiesKeys()) {
                    System.out.println(key + " = " + ams.getProperty(key));
                }

                // Application Management session used to deploy CAPFile
                AMSession deploy = ams.openSession(isdAID)   // select SD & open secure channel
                        .load(sAID_CAP, appFile.getBytes())  // load an application file
                        .install(sAID_CAP,                   // install application
                                sAID_AppletClass, sAID_AppletInstance, installParams)
                        .close();

                testScriptDeploy = new TestScript().append(deploy);

                // Application Management session used to undeploy CAPFile
                AMSession undeploy = ams.openSession(isdAID) // select SD & open secure channel
                        .uninstall(sAID_AppletInstance)      // uninstall the application
                        .unload(sAID_CAP)                    // unload the application code
                        .close();

                testScriptUndeploy = new TestScript().append(undeploy);
            } else {
                // physical card does not need to deploy or undeploy, leave TestScripts empty
                testScriptDeploy = new TestScript();
                testScriptUndeploy = new TestScript();
            }


            CardTerminal t = physical ? getTerminal("pcsc") : getTerminal("socket", "127.0.0.1", "9025");

			// Wait some seconds to allow connections
			if (t.waitForCardPresent(10000)) {
				System.out.println("Connection to simulator established: "+ t.getName());
				Card c = t.connect("*");
                CardChannel channel = c.getBasicChannel();
				System.out.println(getFormattedATR(c.getATR().getBytes()));
                System.out.println("Deploying applet...");
				testScriptDeploy.run(channel);  // deploy applet
                System.out.println("----------------------------CUSTOM-APDUS-----------------------------------");
                this.interactWithApplet(channel); // interact with applet in HWBClientAPDUs
                System.out.println("---------------------------CUSTOM-APDUS-END--------------------------------");
                System.out.println("Undeploying applet...");
				testScriptUndeploy.run(channel); // undeploy applet
				c.disconnect(true);
			}
			else {
				System.out.println("Connection to card failed.");
				iResult = -1;
			}

		} catch (NoSuchAlgorithmException | NoSuchProviderException | CardException | ScriptFailedException | IOException e) {
			e.printStackTrace();
			iResult = -1;
		}
        // try {
        //     Thread.sleep(10000);
        // } catch (Exception e) {
        // }
		System.exit (iResult);
    }

    public static void printAPDU(CommandAPDU apdu) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02X%02X%02X%02X %02X[", apdu.getCLA(), apdu.getINS(), apdu.getP1(), apdu.getP2(), apdu.getNc()));
        for (byte b : apdu.getData()) {
            sb.append(String.format("%02X", b));
        }
        sb.append("]");
        System.out.format("[%1$tF %1$tT %1$tL %1$tZ] [APDU-C] %2$s %n", System.currentTimeMillis(), sb.toString());
    }

    public static void printAPDU(ResponseAPDU apdu) {
        byte[] bytes = apdu.getData();
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        System.out.format("[%1$tF %1$tT %1$tL %1$tZ] [APDU-R] [%2$s] SW:%3$04X %n", System.currentTimeMillis(), sb.toString(), apdu.getSW());
    }

	private static String getArg(String[] args, String argName) throws IllegalArgumentException {
        String value = null;

        for (String param : args) {
            if (param.startsWith("-" + argName + "=")) {
                value = param.substring(param.indexOf('=') + 1);
            }
        }

        if(value == null || value.length() == 0) {
            throw new IllegalArgumentException("Argument " + argName + " is missing");
        }

        return value;
    }

    /**
     * Puts all ATR bytes into a single string using hexadecimal format
     * @param ATR ATR bytes
     * @return Formatted ATR
     */
    private static String getFormattedATR(byte[] ATR) {
        StringBuilder sb = new StringBuilder();
        for (byte b : ATR) {
            sb.append(String.format("%02X ", b));
        }
        return String.format("ATR: [%s]", sb.toString().trim());
    }

	private static CardTerminal getTerminal(String... connectionParams) throws NoSuchAlgorithmException, NoSuchProviderException, CardException {
        TerminalFactory tf;
        String connectivityType = connectionParams[0];
        if (connectivityType.equals("socket")) {
            String ipaddr = connectionParams[1];
            String port = connectionParams[2];
            tf = TerminalFactory.getInstance("SocketCardTerminalFactoryType",
                    List.of(new InetSocketAddress(ipaddr, Integer.parseInt(port))),
                    "SocketCardTerminalProvider");
        } else {
            tf = TerminalFactory.getDefault();
        }
        if (tf.terminals().list().isEmpty()) {
            throw new CardException("No terminal with card found.");
        }
        return tf.terminals().list().get(0);
    }

	public static class TestScript extends APDUScript {
        private List<CommandAPDU>  commands = new LinkedList<>();
        private List<ResponseAPDU> responses = new LinkedList<>();
        private int index = 0;

        public List<ResponseAPDU> run(CardChannel channel) throws ScriptFailedException {
            return super.run(channel, c -> lookupIndex(c), r -> !isExpected(r));
        }

        @Override
        public TestScript append(Scriptable<CardChannel, CommandAPDU, ResponseAPDU> other) {
            super.append(other);
            return this;
        }

        public TestScript append(CommandAPDU apdu, ResponseAPDU expected) {
            super.append(apdu);
            this.commands.add(apdu);
            this.responses.add(expected);
            return this;
        }

        public TestScript append(CommandAPDU apdu) {
            super.append(apdu);
            return this;
        }

        private CommandAPDU lookupIndex(CommandAPDU apdu) {
            // HWBSampleClient.printAPDU(apdu);
            this.index = IntStream.range(0, this.commands.size())
                    .filter(i -> apdu == this.commands.get(i))
                    .findFirst()
                    .orElse(-1);
            return apdu;
        }

        private boolean isExpected(ResponseAPDU response) {

            ResponseAPDU expected = (index < 0)? response : this.responses.get(index);
            if (!response.equals(expected)) {
                System.out.println("Received: ");
                // printAPDU(response);
                System.out.println("Expected: ");
                // printAPDU(expected);
                return false;
            }
            // printAPDU(response);
            return true;
        }
    }
}
