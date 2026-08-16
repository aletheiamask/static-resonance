package com.staticresonance.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

/**
 * Line boxes that ignore depth so the caster can see marks through walls.
 * {@link RenderType#lines()} re-enables depth test on flush, so a dedicated type is required.
 */
public final class SeeThroughRender extends RenderType {
    private static final RenderType LINES = create(
            "sr_see_through_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            256 * 1024,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(ITEM_ENTITY_TARGET)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .createCompositeState(false)
    );

    private SeeThroughRender(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                             boolean crumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, crumbling, sortOnUpload, setupState, clearState);
    }

    public static RenderType lines() {
        return LINES;
    }
}
