package dev.totem.lumen.gameplay.light;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Original asset colors, independent of the player's selected resource packs. */
public final class BlockTextureColor {
    private static final EmissionColor TEXTURELESS = new EmissionColor(1, .86f, .66f);
    private static final Map<String, EmissionColor> colors = new ConcurrentHashMap<>();

    static {
        try (var in = BlockTextureColor.class.getResourceAsStream("/totem-lumen/vanilla-emitter-colors.properties")) {
            if (in != null) {
                var table = new Properties();
                table.load(in);
                for (String name : table.stringPropertyNames()) {
                    int rgb = Integer.parseInt(table.getProperty(name), 16);
                    colors.put("minecraft:" + name, hue(rgb >> 16, rgb >> 8 & 255, rgb & 255));
                }
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private BlockTextureColor() {}

    public static EmissionColor forBlock(String id) {
        return colors.computeIfAbsent(id, key -> {
            try {
                var block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(key));
                if (block.isPresent()) {
                    int rgb = new BlockTextureColor().sample(key, block.get().getStateDefinition().getPossibleStates());
                    if (rgb > 0) return hue(rgb >> 16, rgb >> 8 & 255, rgb & 255);
                }
            } catch (IOException | RuntimeException e) {
                dev.totem.lumen.TotemLumen.LOGGER.debug("Cannot sample original block textures for {}", key, e);
            }
            return TEXTURELESS;
        });
    }

    // Keep the block's emission level while preserving the averaged channel ratios.
    private static EmissionColor hue(int r, int g, int b) {
        float peak = Math.max(r, Math.max(g, b));
        return new EmissionColor(r / peak, g / peak, b / peak);
    }

    private record Sum(double r, double g, double b, double weight) {
        static final Sum ZERO = new Sum(0, 0, 0, 0);

        Sum add(Sum s, double scale) {
            return new Sum(r + s.r * scale, g + s.g * scale, b + s.b * scale, weight + s.weight * scale);
        }

        int rgb() {
            if (weight == 0) return 0;
            return (int)Math.round(r / weight) << 16 | (int)Math.round(g / weight) << 8 | (int)Math.round(b / weight);
        }
    }

    private final Map<String, BufferedImage> images = new HashMap<>();
    private final Map<String, JsonObject> metadata = new HashMap<>();

    /** Used to bake the vanilla table so dedicated servers do not need the Minecraft client assets. */
    public static int sampleOriginal(String id, Iterable<BlockState> states) throws IOException {
        return new BlockTextureColor().sample(id, states);
    }

    private int sample(String id, Iterable<BlockState> states) throws IOException {
        var definition = json(path(id, "blockstates", ".json"));
        var weights = new HashMap<String, Double>();
        for (var state : states) {
            if (state.getLightEmission() <= 0) continue;
            if (definition.has("variants")) {
                for (var variant : definition.getAsJsonObject("variants").entrySet()) {
                    if (matchesVariant(state, variant.getKey())) addModels(variant.getValue(), weights);
                }
            }
            if (definition.has("multipart")) {
                for (var part : definition.getAsJsonArray("multipart")) {
                    var entry = part.getAsJsonObject();
                    if (!entry.has("when") || matches(state, entry.getAsJsonObject("when")))
                        addModels(entry.get("apply"), weights);
                }
            }
        }
        var sum = Sum.ZERO;
        for (var entry : weights.entrySet())
            sum = sum.add(model(entry.getKey()), entry.getValue());
        if (sum.weight == 0) sum = pixels(path(id, "textures/block", ".png"), 0, 0, 16, 16);
        return sum.rgb();
    }

    private static boolean matchesVariant(BlockState state, String variant) {
        if (variant.isEmpty()) return true;
        for (String pair : variant.split(",")) {
            String[] fields = pair.split("=", 2);
            if (fields.length != 2 || !property(state, fields[0], fields[1])) return false;
        }
        return true;
    }

    private static boolean property(BlockState state, String name, String alternatives) {
        var prop = state.getBlock().getStateDefinition().getProperty(name);
        if (prop == null) return false;
        return matchesValue(state, prop, alternatives);
    }

    private static <T extends Comparable<T>> boolean matchesValue(BlockState state,
            Property<T> prop, String alternatives) {
        String value = prop.getName(state.getValue(prop));
        for (String option : alternatives.split("\\|")) if (value.equals(option)) return true;
        return false;
    }

    private static boolean matches(BlockState state, JsonObject conditions) {
        for (var e : conditions.entrySet()) {
            if (e.getKey().equals("OR") || e.getKey().equals("AND")) {
                boolean and = e.getKey().equals("AND");
                boolean result = and;
                for (var nested : e.getValue().getAsJsonArray()) {
                    result = matches(state, nested.getAsJsonObject());
                    if (result != and) break;
                }
                if (!result) return false;
            } else if (!property(state, e.getKey(), e.getValue().getAsString())) return false;
        }
        return true;
    }

    private static void addModels(JsonElement apply, Map<String, Double> weights) {
        if (!apply.isJsonArray()) {
            weights.merge(apply.getAsJsonObject().get("model").getAsString(), 1.0, Double::sum);
            return;
        }
        double total = 0;
        for (var m : apply.getAsJsonArray()) total += integer(m.getAsJsonObject(), "weight", 1);
        for (var m : apply.getAsJsonArray()) {
            var obj = m.getAsJsonObject();
            weights.merge(obj.get("model").getAsString(), integer(obj, "weight", 1) / total, Double::sum);
        }
    }
    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private Sum model(String id) throws IOException {
        var bindings = new HashMap<String, String>();
        JsonArray elements = null;
        Set<String> visited = new HashSet<>();
        for (String current = id; current != null && visited.add(current); ) {
            var model = json(path(current, "models", ".json"));
            if (model.has("textures")) model.getAsJsonObject("textures").entrySet()
                    .forEach(e -> bindings.putIfAbsent(e.getKey(), e.getValue().getAsString()));
            if (elements == null && model.has("elements")) elements = model.getAsJsonArray("elements");
            current = model.has("parent") ? model.get("parent").getAsString() : null;
        }
        var sum = Sum.ZERO;
        if (elements != null) for (var element : elements) {
            var box = element.getAsJsonObject();
            var from = box.getAsJsonArray("from");
            var to = box.getAsJsonArray("to");
            for (var f : box.getAsJsonObject("faces").entrySet()) {
                var face = f.getValue().getAsJsonObject();
                String tex = resolve(face.get("texture").getAsString(), bindings);
                if (tex == null) continue;
                double x = axis(from, 0), y = axis(from, 1), z = axis(from, 2);
                double maxX = axis(to, 0), maxY = axis(to, 1), maxZ = axis(to, 2);
                double[] uv = switch (f.getKey()) {
                    case "down" -> new double[]{x, 16 - maxZ, maxX, 16 - z};
                    case "up" -> new double[]{x, z, maxX, maxZ};
                    case "north" -> new double[]{16 - maxX, 16 - maxY, 16 - x, 16 - y};
                    case "south" -> new double[]{x, 16 - maxY, maxX, 16 - y};
                    case "west" -> new double[]{z, 16 - maxY, maxZ, 16 - y};
                    case "east" -> new double[]{16 - maxZ, 16 - maxY, 16 - z, 16 - y};
                    default -> throw new IOException("Unknown face " + f.getKey());
                };
                double area = Math.abs((uv[2] - uv[0]) * (uv[3] - uv[1]));
                if (face.has("uv")) for (int i = 0; i < 4; i++) uv[i] = axis(face.getAsJsonArray("uv"), i);
                sum = sum.add(pixels(path(tex, "textures", ".png"), uv[0], uv[1], uv[2], uv[3]), area);
            }
        }
        if (sum.weight == 0) {
            String particle = resolve("#particle", bindings);
            if (particle != null) sum = pixels(path(particle, "textures", ".png"), 0, 0, 16, 16);
        }
        return sum;
    }

    private static double axis(JsonArray a, int i) { return a.get(i).getAsDouble(); }

    private static String resolve(String texture, Map<String, String> bindings) {
        var seen = new HashSet<String>();
        while (texture != null && texture.startsWith("#") && seen.add(texture)) texture = bindings.get(texture.substring(1));
        return texture == null || texture.startsWith("#") ? null : texture;
    }

    private Sum pixels(String file, double u0, double v0, double u1, double v1) throws IOException {
        if (!images.containsKey(file)) {
            try (var in = resource(file)) { images.put(file, in == null ? null : ImageIO.read(in)); }
        }
        var image = images.get(file);
        if (image == null) return Sum.ZERO;
        if (!metadata.containsKey(file)) metadata.put(file, json(file + ".mcmeta"));
        var animation = metadata.get(file).getAsJsonObject("animation");
        int width = image.getWidth();
        int height = image.getHeight();
        if (animation != null) {
            int size = Math.min(width, height);
            width = integer(animation, "width", animation.has("height") ? width : size);
            height = integer(animation, "height", animation.has("width") ? height : size);
        }
        if (width <= 0 || height <= 0 || image.getWidth() % width != 0 || image.getHeight() % height != 0)
            return Sum.ZERO;

        int columns = image.getWidth() / width;
        int[] durations = frameDurations(animation, columns * (image.getHeight() / height));
        double left = Math.clamp(Math.min(u0, u1) * width / 16, 0, width);
        double right = Math.clamp(Math.max(u0, u1) * width / 16, 0, width);
        double top = Math.clamp(Math.min(v0, v1) * height / 16, 0, height);
        double bottom = Math.clamp(Math.max(v0, v1) * height / 16, 0, height);
        double red = 0, green = 0, blue = 0, covered = 0, total = 0;
        for (int frame = 0; frame < durations.length; frame++) {
            if (durations[frame] <= 0) continue;
            for (int y = (int) top; y < bottom; y++) {
                for (int x = (int) left; x < right; x++) {
                    double area = (Math.min(x + 1, right) - Math.max(x, left))
                            * (Math.min(y + 1, bottom) - Math.max(y, top)) * durations[frame];
                    int argb = image.getRGB(frame % columns * width + x, frame / columns * height + y);
                    double weight = area * (argb >>> 24) / 255;
                    total += area;
                    covered += weight;
                    red += (argb >> 16 & 255) * weight;
                    green += (argb >> 8 & 255) * weight;
                    blue += (argb & 255) * weight;
                }
            }
        }
        return total == 0 ? Sum.ZERO : new Sum(red / total, green / total, blue / total, covered / total);
    }

    private static int[] frameDurations(JsonObject animation, int count) {
        var times = new int[count];
        if (animation == null || !animation.has("frames")) {
            Arrays.fill(times, 1);
        } else {
            int duration = integer(animation, "frametime", 1);
            for (var entry : animation.getAsJsonArray("frames")) {
                int index = entry.isJsonPrimitive() ? entry.getAsInt() : entry.getAsJsonObject().get("index").getAsInt();
                if (index >= 0 && index < count)
                    times[index] += entry.isJsonObject() ? integer(entry.getAsJsonObject(), "time", duration) : duration;
            }
        }
        return times;
    }

    private static String path(String value, String folder, String suffix) {
        var id = Identifier.parse(value);
        return "/assets/" + id.getNamespace() + "/" + folder + "/" + id.getPath() + suffix;
    }

    private static InputStream resource(String file) {
        return BlockTextureColor.class.getResourceAsStream(file);
    }

    private static JsonObject json(String file) throws IOException {
        try (var in = resource(file)) {
            if (in == null) return new JsonObject();
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
