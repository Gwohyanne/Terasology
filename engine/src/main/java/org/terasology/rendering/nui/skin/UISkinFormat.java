// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.rendering.nui.skin;

import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.ResourceUrn;
import org.terasology.assets.format.AbstractAssetFileFormat;
import org.terasology.assets.format.AssetDataFile;
import org.terasology.assets.module.annotations.RegisterAssetFileFormat;
import org.terasology.nui.Color;
import org.terasology.nui.UITextureRegion;
import org.terasology.nui.UIWidget;
import org.terasology.nui.asset.font.Font;
import org.terasology.nui.skin.UISkin;
import org.terasology.nui.skin.UISkinBuilder;
import org.terasology.nui.skin.UISkinData;
import org.terasology.nui.skin.UIStyleFragment;
import org.terasology.persistence.ModuleContext;
import org.terasology.persistence.serializers.gson.GsonTypeHandlerAdapterFactory;
import org.terasology.persistence.typeHandling.extensionTypes.ColorTypeHandler;
import org.terasology.reflection.metadata.ClassLibrary;
import org.terasology.reflection.metadata.ClassMetadata;
import org.terasology.registry.CoreRegistry;
import org.terasology.rendering.nui.NUIManager;
import org.terasology.utilities.Assets;
import org.terasology.utilities.gson.AssetTypeAdapter;
import org.terasology.utilities.gson.CaseInsensitiveEnumTypeAdapterFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 */
@RegisterAssetFileFormat
public class UISkinFormat extends AbstractAssetFileFormat<UISkinData> {

    private static final Logger logger = LoggerFactory.getLogger(UISkinFormat.class);
    private Gson gson;

    public UISkinFormat() {
        super("skin");
        gson = new GsonBuilder()
            .registerTypeAdapterFactory(new CaseInsensitiveEnumTypeAdapterFactory())
            .registerTypeAdapter(Font.class, new AssetTypeAdapter<>(org.terasology.rendering.assets.font.Font.class))
            .registerTypeAdapter(UISkinData.class, new UISkinTypeAdapter())
            .registerTypeAdapter(UITextureRegion.class, new TextureRegionTypeAdapter())
            .registerTypeAdapterFactory(new GsonTypeHandlerAdapterFactory() {
                {
                    addTypeHandler(Color.class, new ColorTypeHandler());
                }
            })
            .registerTypeAdapter(Optional.class, new OptionalTextureRegionTypeAdapter())
            .create();
    }

    @Override
    public UISkinData load(ResourceUrn urn, List<AssetDataFile> inputs) throws IOException {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inputs.get(0).openStream(), Charsets.UTF_8))) {
            reader.setLenient(true);
            UISkinData data = gson.fromJson(reader, UISkinData.class);
            data.setSource(inputs.get(0));
            return data;
        } catch (JsonParseException e) {
            throw new IOException("Failed to load skin '" + urn + "'", e);
        }
    }

    public UISkinData load(JsonElement element) throws IOException {
        return gson.fromJson(element, UISkinData.class);
    }

    private static class TextureRegionTypeAdapter implements JsonDeserializer<UITextureRegion> {

        @Override
        public UITextureRegion deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            String uri = json.getAsString();
            return Assets.getTextureRegion(uri).orElse(null);
        }
    }

    private static class UISkinTypeAdapter implements JsonDeserializer<UISkinData> {
        @Override
        public UISkinData deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json.isJsonObject()) {
                UISkinBuilder builder = new UISkinBuilder();
                DefaultInfo defaultInfo = context.deserialize(json, DefaultInfo.class);
                defaultInfo.apply(builder);
                return builder.build();
            }
            return null;
        }
    }

    private static class DefaultInfo extends FamilyInfo {
        public String inherit;
        public Map<String, FamilyInfo> families;

        @Override
        public void apply(UISkinBuilder builder) {
            super.apply(builder);
            if (inherit != null) {
                Optional<? extends UISkin> skin = Assets.get(inherit, UISkin.class);
                if (skin.isPresent()) {
                    builder.setBaseSkin(skin.get());
                }
            }
            if (families != null) {
                for (Map.Entry<String, FamilyInfo> entry : families.entrySet()) {
                    builder.setFamily(entry.getKey());
                    entry.getValue().apply(builder);
                }
            }
        }
    }

    private static class FamilyInfo extends StyleInfo {
        public Map<String, ElementInfo> elements;

        public void apply(UISkinBuilder builder) {
            super.apply(builder);
            if (elements != null) {
                for (Map.Entry<String, ElementInfo> entry : elements.entrySet()) {
                    ClassLibrary<UIWidget> library = CoreRegistry.get(NUIManager.class).getWidgetMetadataLibrary();
                    ClassMetadata<? extends UIWidget, ?> metadata = library.resolve(entry.getKey(), ModuleContext.getContext());
                    if (metadata != null) {
                        builder.setElementClass(metadata.getType());
                        entry.getValue().apply(builder);
                    } else {
                        logger.warn("Failed to resolve UIWidget class {}, skipping style information", entry.getKey());
                    }

                }
            }
        }
    }

    private static class PartsInfo extends StyleInfo {
        public Map<String, StyleInfo> modes;

        public void apply(UISkinBuilder builder) {
            super.apply(builder);
            if (modes != null) {
                for (Map.Entry<String, StyleInfo> entry : modes.entrySet()) {
                    builder.setElementMode(entry.getKey());
                    entry.getValue().apply(builder);
                }
            }
        }
    }

    private static class ElementInfo extends StyleInfo {
        public Map<String, PartsInfo> parts;
        public Map<String, StyleInfo> modes;

        public void apply(UISkinBuilder builder) {
            super.apply(builder);
            if (modes != null) {
                for (Map.Entry<String, StyleInfo> entry : modes.entrySet()) {
                    builder.setElementMode(entry.getKey());
                    entry.getValue().apply(builder);
                }
            }
            if (parts != null) {
                for (Map.Entry<String, PartsInfo> entry : parts.entrySet()) {
                    builder.setElementPart(entry.getKey());
                    entry.getValue().apply(builder);
                }
            }
        }
    }

    private static class StyleInfo extends UIStyleFragment {

        private void apply(UISkinBuilder builder) {
            builder.setStyleFragment(this);
        }
    }

    private static class OptionalTextureRegionTypeAdapter implements JsonDeserializer<Optional<?>> {
        @Override
        public Optional<?> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Assets.getTextureRegion(json.getAsString());
        }
    }
}
