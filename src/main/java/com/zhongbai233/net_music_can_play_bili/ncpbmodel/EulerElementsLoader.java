package com.zhongbai233.net_music_can_play_bili.ncpbmodel;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.core.Direction;
import net.minecraft.util.GsonHelper;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import org.joml.Vector3f;

/**
 * {@code "loader": "net_music_can_play_bili:euler_elements"} 的解析器。
 *
 * <p>JSON 形态：</p>
 * <pre>
 * {
 *   "loader": "net_music_can_play_bili:euler_elements",
 *   "parent": "...",                       // 可选，照常生效（贴图/display 继承）
 *   "textures": { ... },
 *   "elements": [ ... ],                   // 普通元素，仍然是 1.21.1 合法写法
 *   "ncpb_euler_elements": [               // 本模组扩展：允许任意角度、三轴
 *     {
 *       "from": [..], "to": [..],
 *       "rotation": { "origin": [..], "x": 90, "y": 0, "z": 0, "rescale": false },
 *       "faces": { "north": { "uv": [..], "texture": "#0" }, ... }
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>{@code rotation} 的口径与 26.1.2 的 {@code CuboidRotation$EulerXYZRotation} 一致：
 * {@code origin} 是 0~16 的方块内坐标，{@code x/y/z} 是角度（度）。</p>
 */
public final class EulerElementsLoader implements IGeometryLoader<EulerElementsGeometry> {

    /** 扩展字段名。原版 {@code BlockModel.Deserializer} 不认识它，会直接忽略，因此 JSON 仍然合法。 */
    public static final String EULER_ELEMENTS = "ncpb_euler_elements";

    private static final String ELEMENTS = "elements";
    private static final Type BLOCK_ELEMENT_LIST = new TypeToken<List<BlockElement>>() { }.getType();

    @Override
    public EulerElementsGeometry read(JsonObject json, JsonDeserializationContext context) throws JsonParseException {
        List<BlockElement> plain = json.has(ELEMENTS)
                ? context.<List<BlockElement>>deserialize(json.get(ELEMENTS), BLOCK_ELEMENT_LIST)
                : List.of();
        List<EulerElement> euler = new ArrayList<>();
        if (json.has(EULER_ELEMENTS)) {
            for (JsonElement raw : GsonHelper.getAsJsonArray(json, EULER_ELEMENTS)) {
                euler.add(readElement(GsonHelper.convertToJsonObject(raw, EULER_ELEMENTS), context));
            }
        }
        return new EulerElementsGeometry(plain, euler);
    }

    private static EulerElement readElement(JsonObject json, JsonDeserializationContext context) {
        Vector3f from = readVector3f(json, "from");
        Vector3f to = readVector3f(json, "to");
        boolean shade = GsonHelper.getAsBoolean(json, "shade", true);

        JsonObject facesJson = GsonHelper.getAsJsonObject(json, "faces");
        Map<Direction, BlockElementFace> faces = new EnumMap<>(Direction.class);
        for (Map.Entry<String, JsonElement> entry : facesJson.entrySet()) {
            Direction direction = Direction.byName(entry.getKey());
            if (direction == null) {
                throw new JsonSyntaxException("Unknown facing: " + entry.getKey());
            }
            // 与原版 BlockElement$Deserializer.filterNullFromFaces 一致：解出 null 的条目丢掉。
            BlockElementFace face = context.deserialize(entry.getValue(), BlockElementFace.class);
            if (face != null) {
                faces.put(direction, face);
            }
        }
        if (faces.isEmpty()) {
            throw new JsonSyntaxException("Expected between 1 and 6 unique faces, got 0（" + EULER_ELEMENTS + "）");
        }

        // rotation 走本模组自己的解析，绕开 BlockElement$Deserializer 的「单轴 + 5 个角度」限制。
        JsonObject rotation = GsonHelper.getAsJsonObject(json, "rotation");
        // origin 是 0~16 的方块内坐标，和原版一样乘 0.0625 换成方块单位。
        Vector3f origin = readVector3f(rotation, "origin").mul(0.0625F);
        float x = GsonHelper.getAsFloat(rotation, "x", 0.0F);
        float y = GsonHelper.getAsFloat(rotation, "y", 0.0F);
        float z = GsonHelper.getAsFloat(rotation, "z", 0.0F);
        if (rotation.has("rescale") && GsonHelper.getAsBoolean(rotation, "rescale", false)) {
            // 已知范围内没有元素用到 rescale；真出现时需要像原版那样按 22.5/45 缩放顶点。
            throw new JsonParseException("ncpb_euler_elements 暂不支持 rescale=true（rotation.rescale）");
        }

        // rotation 传 null：旋转由 EulerElement 提供的 ModelState 负责。
        BlockElement element = new BlockElement(from, to, faces, null, shade);
        return new EulerElement(element, origin, x, y, z);
    }

    private static Vector3f readVector3f(JsonObject json, String name) {
        JsonArray array = GsonHelper.getAsJsonArray(json, name);
        if (array.size() != 3) {
            throw new JsonSyntaxException("Expected 3 values for " + name + ", got " + array.size());
        }
        return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }
}
