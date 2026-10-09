package org.wanaku.wsr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.camel.main.Main;
import org.apache.camel.support.PropertyBindingSupport;

/** Native Camel property conversion for trusted deployment-owned expert settings. */
final class ExpertProperties {
    private ExpertProperties() {}

    /** Binds trusted deployment settings before Camel owns the expert lifecycle. */
    static void bind(Main main, Properties settings, String name, Object bean) {
        String prefix = "wsr.expert." + name + ".properties.";
        Map<String, Object> properties = new LinkedHashMap<>();
        try {
            for (String key : settings.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    properties.put(
                            key.substring(prefix.length()),
                            main.getCamelContext().resolvePropertyPlaceholders(settings.getProperty(key)));
                }
            }
            PropertyBindingSupport.bindProperties(main.getCamelContext(), bean, properties);
            if (!properties.isEmpty()) {
                throw new IllegalArgumentException("Unknown expert deployment property");
            }
        } catch (Exception failure) {
            // Conversion and setter errors can contain credentials or model paths.
            throw new IllegalArgumentException("Unable to configure expert deployment properties");
        }
    }
}
