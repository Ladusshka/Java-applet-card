tutorials/03.adoc, poslední změna d5fea346 (7. 10. 2025 v 8:27, Jiří Buček)
# Java Card Applet Lab

This handout describes three Java Card exercises: basic APDU handling, PIN-protected commands, and AES encryption with message authentication.

## 1. Basic Applet

Create a Java Card applet that supports these instructions:

| INS | Behavior |
| --- | --- |
| `0x00` | Return your name encoded as ASCII. |
| `0x02` | Accept up to 20 bytes and store both the data and its length. |
| `0x04` | Return the data stored by `0x02` when its length matches the requested response length (`Le`). |



Test the applet in the Java Card simulator (Eclipse on Windows). Verify normal operation and handle these cases:

| Condition | Expected status word |
| --- | --- |
| Unsupported instruction | `ISO7816.SW_INS_NOT_SUPPORTED` (`0x6D00`) |
| Unsupported `CLA` | `ISO7816.SW_CLA_NOT_SUPPORTED` (`0x6E00`) |
| `INS=0x02` body is too long | `ISO7816.SW_WRONG_LENGTH` (`0x6700`) |
| `INS=0x04` requested length is incorrect | `0x6Cxx`, where `xx` is the number of bytes stored on the card. You can calculate this as `ISO7816.SW_CORRECT_LENGTH_00` (`0x6C00`) plus the correct length. |

For background, see the Cardwerk references on the [instruction byte](https://cardwerk.com/smart-card-standard-iso7816-4-section-5-basic-organizations/) and [class byte](https://cardwerk.com/smart-card-standard-iso7816-4-section-5-basic-organizations/). If time permits, try loading the applet onto a physical card; this will be covered in a later lab.

## 2. Card-to-Terminal Communication

### Command APDU

The command APDU has a required header and optional body fields:

| Header (required) |  |  |  | Body (optional) |  |  |
| --- | --- | --- | --- | --- | --- | --- |
| `CLA` | `INS` | `P1` | `P2` | `Lc` | Data | `Le` |

Use `CLA=0x80` for proprietary commands. Use `CLA=0x00` for standard commands such as `SELECT` (see ISO 7816-4). Choose the `INS` byte for your commands and check it in the applet. Use `P1` and `P2` as needed.

When sending data to the card, include `Lc` followed by that many data bytes. Omit these fields when there is no command data. When expecting response data, include `Le` to specify the expected response length; omit it when no response data is expected. APDU scripts may differ from the standard; see the simulator documentation for details.

### Response APDU

| Response body (optional) | Response trailer (required) |
| --- | --- |
| Data | `SW1`, `SW2` |

The optional command and response fields form four command-response cases:

| Case | Command APDU | Response APDU |
| --- | --- | --- |
| 1 | `CLA INS P1 P2` | `SW1 SW2` |
| 2 | `CLA INS P1 P2 Le` | Data, `SW1 SW2` |
| 3 | `CLA INS P1 P2 Lc Data` | `SW1 SW2` |
| 4 | `CLA INS P1 P2 Lc Data Le` | Data, `SW1 SW2` |

## 3. Implementing APDU Communication

The terminal always initiates communication. The applet receives each APDU as a parameter to `process`. The APDU header is available first through the APDU buffer; incoming data cannot be read or outgoing data sent until the APDU is moved to the appropriate state.

```java
byte[] buf = apdu.getBuffer();
```

The header bytes `CLA`, `INS`, `P1`, and `P2` are at offsets 0, 1, 2, and 3. Symbolic constants are available, such as `ISO7816.OFFSET_CLA`. At offset 4 is a byte whose meaning is `Lc` or `Le`, depending on the APDU case.

```java
byte ins = buf[ISO7816.OFFSET_INS];
switch (ins) {
    case 0x01:
        // Handle the instruction.
        break;
}
```

### Receiving Data

Move the APDU into its incoming state and receive the data. This example assumes all incoming data fits in the APDU buffer; longer data would need to be handled in multiple chunks.

```java
short len = apdu.setIncomingAndReceive();
```

The data is now in the APDU buffer starting at `ISO7816.OFFSET_CDATA`. For example, copy it to the beginning of an array:

```java
Util.arrayCopy(buf, ISO7816.OFFSET_CDATA, array, (short) 0, len);
// Or, when an atomic copy is not required:
Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, array, (short) 0, len);
```

### Sending Data

To send response data, call `setOutgoing()` to obtain the expected response length (`Le`), set the number of bytes to send with `setOutgoingLength(short length)`, then send the data. The outgoing length may differ from `Le`.

Use `sendBytes()` when the response is already in the APDU buffer, or `sendBytesLong()` when it is in another array:

```java
len = apdu.setOutgoing();
if (len > array.length) {
    len = (short) array.length;
}
apdu.setOutgoingLength(len);
apdu.sendBytesLong(array, (short) 0, len);
```

### Sending a Status Word

Every response includes a status word (`SW1`, `SW2`). Call `ISOException.throwIt(short statusWord)` to end the response with the specified status word.

The first APDU is typically a `SELECT` command, such as `00 A4 04 00 Lc ...applet AID...`. The applet does not need to return data for this command, but it must confirm that it was selected:

```java
if (selectingApplet()) {
    ISOException.throwIt(ISO7816.SW_NO_ERROR);
}
```

Standard status-word values are listed in ISO 7816 references.

## 4. PIN-Protected Applet

First, compile and load the previous applet onto a card, then test it on the card. Next, create a new applet that:

- Supports the instructions from the previous exercise, with some commands requiring PIN verification.
- Sets the PIN during applet installation using installation parameters.
- Verifies the PIN with `VERIFY` (`INS=0x20`). Every instruction except returning the name must check whether the PIN has been verified.
- Returns `0x9000` when `VERIFY` succeeds and `0x6300` (`SW_VERIFICATION_FAILED`) when it fails.
- Refuses to operate after three or more failed PIN attempts. The applet must not be selectable; see `Applet.select()`, which can return `false`.
- Returns `SW_PIN_VERIFICATION_REQUIRED` (`0x6301`) when a PIN-protected instruction is called before the PIN has been verified.

Compile and load the new applet onto the card, then test it on the card.

**Implementation tip:** Give each instruction its own handler method. Keep `process` focused on validating the APDU header, decoding the instruction, and dispatching to the appropriate handler.

## 5. AES Encryption and MAC

Create an applet that uses AES to encrypt command-APDU data and generate a message authentication code (MAC). It must also decrypt previously encrypted data and verify its MAC.

### Required Instructions

| INS | Behavior | Maximum input |
| --- | --- | --- |
| `0x42` | Encrypt the input and return the ciphertext followed by its MAC. | 64 bytes |
| `0x44` | Verify the MAC, decrypt the input, and return the plaintext. | 80 bytes total: up to 64 bytes of ciphertext plus a 16-byte MAC |

For `0x42`, the response contains `x * 16` bytes of ciphertext followed by a 16-byte MAC. If an error occurs, return no data; report the error only in the status word. For `0x44`, likewise return no data if decryption or MAC verification fails.

Use AES-128 in CBC mode for encryption and decryption, and AES-128 in CBC mode for the MAC. The encryption and MAC keys may be hard-coded, but they must be different. The IV may also be hard-coded, or left at its default value (all zero bytes). Use the **Encrypt-then-MAC** construction: encrypt the data first, then calculate the MAC over the ciphertext.

AES is a block cipher, so input lengths must be multiples of the 16-byte block size. Padding is not used in this exercise; reject input whose length is not a multiple of 16 bytes.

> **Security note:** Omitting padding and using a fixed or zero IV can create security vulnerabilities. A production implementation would need a more careful design, for example a randomly generated IV and PKCS#7 padding or ciphertext stealing. These measures are not required for this exercise.

### Expected Status Words

Test the applet on a physical card and demonstrate its correct behavior. Expected status words include:

| Result | Status word |
| --- | --- |
| Success | `ISO7816.SW_NO_ERROR` (`0x9000`) |
| Unsupported `CLA` | `ISO7816.SW_CLA_NOT_SUPPORTED` (`0x6E00`) |
| Unsupported `INS` | `ISO7816.SW_INS_NOT_SUPPORTED` (`0x6D00`) |
| Body is too long | `ISO7816.SW_WRONG_LENGTH` (`0x6700`) |
| Data length is not valid for AES (no padding) | `ISO7816.SW_WRONG_LENGTH` |
| MAC verification fails | `ISO7816.SW_WRONG_DATA` (`0x6A80`) |

### Java Card Cryptography APIs

Creating cryptographic objects allocates memory. Create these objects in the applet constructor and reuse them while processing commands.

- **`AESKey`** encapsulates the key used by AES. Create it with `KeyBuilder.buildKey(...)` and set its value with `setKey(...)`.

  ```java
  AESKey keyEnc = (AESKey) KeyBuilder.buildKey(
      KeyBuilder.TYPE_xxx,
      KeyBuilder.LENGTH_xxx_xxx,
      false);
  ```

- **`Cipher`** provides encryption and decryption. Create an instance with `Cipher.getInstance(...)`, then initialize it with the key and, if needed, an IV. `update` and `doFinal` process data; `doFinal` is sufficient for this exercise because the complete input is processed at once. It accepts input data and returns the processed length.

  Use these methods correctly and catch any exceptions they may throw. For example, `doFinal` can throw `CryptoException` with reason `CryptoException.ILLEGAL_USE` when the input is not block-aligned. The exception's reason is available through `CryptoException.getReason()`.

- **`Signature`** provides signature and MAC algorithms, including HMAC, RSA, and ECDSA. Create an instance with `getInstance(...)` using the appropriate algorithm, then initialize it with the key and any required parameters. `sign` computes the MAC and returns its length; `verify` returns `true` when the MAC is valid and `false` otherwise. `update` is available for incremental processing but is not needed here.

  For example, calculate the MAC over the ciphertext and append it to the same array:

  ```java
  short lenMac = aesMac.sign(encryptedData, (short) 0, lenEnc,
                            encryptedData, lenEnc);
  ```

The physical card uses Java Card API 2.2.2. Refer to the Java Card 2.2.2 API documentation for the classes and methods in `javacard.security` and `javacardx.crypto`.

**Tip:** Use transient arrays for temporary data. Allocate them with `JCSystem.makeTransientByteArray()` (remember that this allocates an object, so do it during applet construction). Alternatively, reuse the already allocated APDU buffer after receiving the data. The standard guarantees a buffer of at least 127 bytes.

---

Source: `tutorials/03.adoc` · Last updated in source revision `d5fea346` on October 7, 2025 by Jiří Buček.
