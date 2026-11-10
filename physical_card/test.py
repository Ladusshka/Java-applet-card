from smartcard.System import readers
from smartcard.util import toHexString


# Establish a connection to the first reader that has a card inserted
def establish_context():
    r = readers()
    if not r:
        raise Exception("No smartcard readers found.")

    for reader in r:
        print(f"Checking reader: {reader}")
        connection = reader.createConnection()
        try:
            connection.connect()  # Try to connect to the card
            print(f"Card found in reader: {reader}")
            return connection  # Return the first reader with a card inserted
        except:
            print(f"No card detected in reader: {reader}")
            continue

    raise Exception("No card detected in any of the readers.")

# Helper function to send APDU command to the card and receive response
def send_apdu(card, apdu):
    print(f"Sending APDU: {toHexString(apdu)}")
    response, sw1, sw2 = card.transmit(apdu)
    print(f"Response: {toHexString(response)}, SW1: {sw1:02x}, SW2: {sw2:02x}")
    return response, sw1, sw2

# APDU definitions
def test_applet():
    card = establish_context()
    try:
        print("Card connected.")

        print("# Test: Select applet by AID (FirstApplet)");
        send_apdu(card, [0x00, 0xA4, 0x04, 0x00, 0x07, 0x11, 0x22, 0x33, 0x44, 0x55, 0x00, 0x01])

        print("# Get Name")
        name, sw1, sw2 = send_apdu(card, [0x80, 0x00, 0x00, 0x00, 0x05])
        print(f"{bytes(name) = }")


    except Exception as e:
        print(f"An error occurred: {e}")
    finally:
        # Disconnect the card and release the context
        card.disconnect()
        print("Card disconnected.")

if __name__ == "__main__":
    test_applet()
