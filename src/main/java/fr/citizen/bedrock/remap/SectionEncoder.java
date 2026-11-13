package fr.citizen.bedrock.remap;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Écrit une section de 16x16x16 blocs exactement comme un client vanilla 1.21.1 s'attend à la lire :
 *  - 1 seul bloc : bits = 0, l'identifiant, tableau vide ;
 *  - jusqu'à 16 blocs différents : palette sur 4 bits ;
 *  - jusqu'à 256 : palette sur 5 à 8 bits ;
 *  - au-delà : palette globale avec le nombre de bits vanilla.
 */
public final class SectionEncoder {
    private SectionEncoder() {
    }

    public static int vanillaId(BlockState state) {
        return RemapTables.remapBlockState(Block.BLOCK_STATE_REGISTRY.getId(state));
    }

    private record Encoded(int bits, int[] palette, long[] data) {
    }

    private static Encoded encode(int[] ids) {
        Int2IntOpenHashMap index = new Int2IntOpenHashMap();
        index.defaultReturnValue(-1);
        IntArrayList palette = new IntArrayList();
        for (int id : ids) {
            if (index.get(id) == -1) {
                index.put(id, palette.size());
                palette.add(id);
            }
        }
        if (palette.size() == 1) {
            return new Encoded(0, new int[]{palette.getInt(0)}, new long[0]);
        }
        int bits = Mth.ceillog2(palette.size());
        boolean global = bits > 8;
        if (global) {
            bits = RemapTables.globalBits();
        } else if (bits < 4) {
            bits = 4;
        }
        int perLong = 64 / bits;
        long[] data = new long[(ids.length + perLong - 1) / perLong];
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < ids.length; i++) {
            long value = (global ? ids[i] : index.get(ids[i])) & mask;
            int cell = i / perLong;
            int shift = (i - cell * perLong) * bits;
            data[cell] |= value << shift;
        }
        return new Encoded(bits, global ? null : palette.toIntArray(), data);
    }

    public static void write(FriendlyByteBuf buf, int[] ids) {
        Encoded e = encode(ids);
        buf.writeByte(e.bits());
        if (e.palette() != null) {
            if (e.bits() == 0) {
                buf.writeVarInt(e.palette()[0]);
            } else {
                buf.writeVarInt(e.palette().length);
                for (int id : e.palette()) {
                    buf.writeVarInt(id);
                }
            }
        }
        buf.writeVarInt(e.data().length);
        for (long l : e.data()) {
            buf.writeLong(l);
        }
    }

    public static int size(int[] ids) {
        Encoded e = encode(ids);
        int size = 1;
        if (e.palette() != null) {
            if (e.bits() == 0) {
                size += VarInt.getByteSize(e.palette()[0]);
            } else {
                size += VarInt.getByteSize(e.palette().length);
                for (int id : e.palette()) {
                    size += VarInt.getByteSize(id);
                }
            }
        }
        size += VarInt.getByteSize(e.data().length);
        size += e.data().length * 8;
        return size;
    }
}
