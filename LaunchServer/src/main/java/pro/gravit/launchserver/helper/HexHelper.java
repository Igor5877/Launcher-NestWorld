package pro.gravit.launchserver.helper;

public final class HexHelper {

    private static final String HEX = "0123456789abcdef";

    private HexHelper() {
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        int offset = 0;
        char[] hex = new char[bytes.length << 1];
        for (byte currentByte : bytes) {
            int ub = Byte.toUnsignedInt(currentByte);
            hex[offset] = HEX.charAt(ub >>> 4);
            offset++;
            hex[offset] = HEX.charAt(ub & 0x0F);
            offset++;
        }
        return new String(hex);
    }
}
