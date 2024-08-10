package ivorius.reccomplex.world;

import ivorius.ivtoolkit.blocks.BlockSurfacePos;
import ivorius.ivtoolkit.util.IvStreams;
import net.minecraft.util.math.ChunkPos;

import java.util.stream.IntStream;
import java.util.stream.Stream;

// TODO Temp drop-in for an unreleased change to the IvToolkit API.
public class RCChunks {
    public static IntStream repeatsInChunk(int chunkPos, int shift, int repeatLength) {
        if (repeatLength == 0) {
            return shift >> 4 == chunkPos
                ? IntStream.of(shift)
                : IntStream.empty();
        }

        int lowest = shift + ((chunkPos << 4) - shift) / repeatLength * repeatLength;
        return IntStream.range(0, repeatLength + 1).map(x -> lowest + x * repeatLength);
    }

    public static Stream<BlockSurfacePos> repeatIntersections(ChunkPos chunkPos, BlockSurfacePos pos, int repeatX, int repeatZ) {
        IntStream xStream = repeatsInChunk(chunkPos.x, pos.x, repeatX);
        IntStream zStream = repeatsInChunk(chunkPos.z, pos.z, repeatZ);

        return IvStreams.flatMapToObj(xStream, x ->
            zStream.mapToObj(z -> new BlockSurfacePos(x, z))
        );
    }
}
