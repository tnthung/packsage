package dev.packsage.journal;

/** An inclusive integer bounding box. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static Box of(int x, int y, int z) {
        return new Box(x, y, z, x, y, z);
    }

    public Box include(int x, int y, int z) {
        return new Box(Math.min(minX, x), Math.min(minY, y), Math.min(minZ, z),
                Math.max(maxX, x), Math.max(maxY, y), Math.max(maxZ, z));
    }

    public Box union(Box other) {
        return new Box(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    public Box grow(int margin) {
        return new Box(minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean intersects(Box other) {
        return other.maxX >= minX && other.minX <= maxX && other.maxY >= minY && other.minY <= maxY
                && other.maxZ >= minZ && other.minZ <= maxZ;
    }
}
