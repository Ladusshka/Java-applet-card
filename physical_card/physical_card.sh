#!/usr/bin/env bash
set -euo pipefail

# !!! set the path of the extracted .zip file from courses here if its not in the $HOME folder !!!
JAVA_CARD_PATH="$HOME/java_card"
# !!!

# ========================
# Defaults
# ========================
PACKAGE="hwbsample"
APPLET="HWBSample"
# PACKAGE_AID="0x01:0x02:0x03:0x04:0x05:0x06:0x08"
PACKAGE_AID="0x11:0x22:0x33:0x44:0x55:0x00"
# APPLET_AID="0x01:0x02:0x03:0x04:0x05:0x06:0x08:0x09"
APPLET_AID="0x11:0x22:0x33:0x44:0x55:0x00:0x01"
JAVA_HOME="/usr/lib/jvm/jdk-8/"
JC_HOME="$JAVA_CARD_PATH/java_card_kit_2_2_2_linux"
GP_JAR="$JAVA_CARD_PATH/gp.jar"
SRC_DIR="$JAVA_CARD_PATH/projects/HWBSample/applet/src"

# ========================
# Environment setup
# ========================
export PATH="$JAVA_HOME/bin:$JC_HOME/bin:$JAVA_CARD_PATH:$JAVA_CARD_PATH/gpshell:$PATH"

# ========================
# Functions
# ========================

# compilation and conversion -> creation of .cap file from Java source
compile() {
    echo "[*] Compiling $PACKAGE.$APPLET..."
    javac -g -target 1.1 -source 1.2 \
        -cp "$JC_HOME/lib/api.jar" \
        -d . "$SRC_DIR/$PACKAGE"/*.java

    echo "[*] Converting to CAP..."
    converter \
        -exportpath "$JC_HOME/api_export_files" \
        -applet "$APPLET_AID" "$PACKAGE.$APPLET" \
        "$PACKAGE" "$PACKAGE_AID" 1.0
}

# install the .cap file to the physical card
install() {
    # if installation parameters are present, give them
    if [ -z "$INSTALL_PARAMS" ]; then
        PARAM_OPTION=""
    else
        PARAM_OPTION="--params $INSTALL_PARAMS"
    fi

    echo "[*] Installing CAP file..."
    java -jar "$GP_JAR" --install "$PACKAGE/javacard/$PACKAGE.cap" -f $PARAM_OPTION
}

# send APDUs from given file to the applet
shell() {
    local file="${1:-}"
    echo "[*] Sending APDUs from $file..."
    # .so library is in the same folder as the gpshell executable, add it to linker PATH
    LD_LIBRARY_PATH="$JAVA_CARD_PATH/gpshell":$LD_LIBRARY_PATH gpshell "$file"
}

# delete the applet package from the physical card
delete() {
    # delete needs AID in plain format
    # convert PACKAGE_AID hex -> plain concatenated bytes
    local aid
    aid="$(aid_plain_from_hex "$PACKAGE_AID")"

    if [[ -z "$aid" ]]; then
        echo "[ERROR] PACKAGE_AID is empty after conversion to plain format."
        exit 1
    fi

    echo "[*] Deleting package with AID: $aid"
    java -jar "$GP_JAR" --delete "$aid"
    echo "[+] Delete finished."
}

# list applets installed on the physical card
list() {
    echo "[*] Listing installed applets..."
    java -jar "$GP_JAR" --list
}


usage() {
    cat <<EOF
Usage: $0 [options]

Actions (default: compile + install):
  -c            Compile applet
  -i            Install applect
  -s file.txt   Send APDUs to applet (APDUs from file.txt)
  -d            Delete applet package
  -l            List installed applets

  -a            Do compile + install [default]

  Multiple actions can be specified to be executed, they are always done in this order:
    compile -> install -> send APDUs -> delete -> list

Options (leaving default is probably fine):
  -p HEXSTRING Instalation parameters (no installation parameters given by default)
  -P AID       Package AID (default: $PACKAGE_AID)
  -A AID       Applet AID (default: $APPLET_AID)
  -q NAME      Package name (default: $PACKAGE)
  -x NAME      Applet class name (default: $APPLET)
  -h           Show this help
EOF
}

# ========================
# Helpers
# ========================

# convert AID in hex format: "0x01:0x02:0x03:0x04:0x5:0x6:0x8:0x9"
# to plain format: "0102a30d05060809"
aid_plain_from_hex() {
    local input="$1"
    local out=""
    IFS=':' read -ra parts <<< "$input"
    for p in "${parts[@]}"; do
        # remove 0x/0X prefix and any non-hex characters
        p="${p#0x}"; p="${p#0X}"
        p="$(echo "$p" | tr -cd '[:xdigit:]')"
        [[ -z "$p" ]] && continue
        # convert hex -> number, then format as two lowercase hex digits
        printf -v byte "%02x" "$((16#$p))"
        out+="$byte"
    done
    printf '%s' "$out"
}

# ========================
# Parse arguments
# ========================
DO_COMPILE=false
DO_INSTALL=false
DO_SHELL=false
DO_DELETE=false
DO_LIST=false
INSTALL_PARAMS=""

while getopts "cildaq:x:P:A:s:p:h" opt; do
    case $opt in
        c) DO_COMPILE=true ;;
        i) DO_INSTALL=true ;;
        l) DO_LIST=true ;;
        d) DO_DELETE=true ;;
        a) DO_COMPILE=true; DO_INSTALL=true ;;
        q) PACKAGE="$OPTARG" ;;
        x) APPLET="$OPTARG" ;;
        P) PACKAGE_AID="$OPTARG" ;;
        A) APPLET_AID="$OPTARG" ;;
        s) DO_SHELL=true; SHELL_FILE="$OPTARG" ;;
        p) INSTALL_PARAMS="$OPTARG" ;;
        h) usage; exit 0 ;;
        *) usage; exit 1 ;;
    esac
done
shift $((OPTIND -1))

# Default action = compile + install if no explicit action chosen
if ! $DO_COMPILE && ! $DO_INSTALL && ! $DO_DELETE && ! $DO_LIST && ! $DO_SHELL; then
    DO_COMPILE=true
    DO_INSTALL=true
fi

# ========================
# Execute actions
# ========================
$DO_COMPILE && compile
$DO_INSTALL && install
$DO_SHELL && shell "$SHELL_FILE"
$DO_DELETE && delete
$DO_LIST && list
