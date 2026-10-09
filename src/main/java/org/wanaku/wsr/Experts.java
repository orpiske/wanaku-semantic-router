package org.wanaku.wsr;

import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.camel.CamelContextAware;
import org.apache.camel.Service;
import org.apache.camel.main.Main;
import org.apache.camel.semantic.SemanticAdapter;

/** Instantiate curated deployment beans. Provider transport remains owned by Camel. */
final class Experts {
    private static final String TYPESAFE_ADAPTER = "org.apache.camel.component.typesafeai.TypeSafeAiSemanticAdapter";
    private static final String TYPESAFE_DEPENDENCY =
            "org.apache.camel:camel-typesafe-ai:" + CatalogLoader.CAMEL_VERSION;

    private Experts() {}

    static void defaults(Properties settings, String selected, String dependency, Map<String, String> environment) {
        if (!settings.containsKey("wsr.experts")) {
            if (!TYPESAFE_DEPENDENCY.equals(dependency)) {
                throw new IllegalArgumentException("Selected expert requires an explicit --expert setting");
            }
            settings.setProperty("wsr.experts", selected);
            settings.putIfAbsent("wsr.expert." + selected + ".class", TYPESAFE_ADAPTER);
        }
        if (TYPESAFE_ADAPTER.equals(settings.getProperty("wsr.expert." + selected + ".class"))) {
            settings.putIfAbsent("camel.component.typesafe-ai.api-key", "{{env:TYPESAFE_API_KEY}}");
            String model = environment.get("TYPESAFE_MODEL");
            if (model != null && !model.isBlank()) {
                settings.putIfAbsent("camel.component.typesafe-ai.model", "{{env:TYPESAFE_MODEL}}");
            }
        }
    }

    static void configure(Main main, Properties settings, String selected) throws Exception {
        String published = settings.getProperty("wsr.semantic-route.expert-bean");
        if (published != null && !published.equals(selected)) {
            throw new IllegalArgumentException("Catalog expert differs from selected publication");
        }
        String allowed = RuntimeSettings.required(settings, "wsr.experts");
        boolean found = false;
        Set<String> enabled = new HashSet<>();
        for (String name : allowed.split(",")) {
            name = RuntimeSettings.identifier(name.trim());
            if (name.equals(selected)) {
                found = true;
            }
            if (!enabled.add(name)) {
                throw new IllegalArgumentException("Duplicate configured expert bean");
            }
            String className = RuntimeSettings.required(settings, "wsr.expert." + name + ".class");
            Class<?> type = Thread.currentThread().getContextClassLoader().loadClass(className);
            Object bean = type.getDeclaredConstructor().newInstance();
            if (!(bean instanceof SemanticAdapter)) {
                throw new IllegalArgumentException("Configured expert does not implement SemanticAdapter");
            }
            main.bind(name, bean);
        }
        String guard = settings.getProperty("wsr.semantic-route.guard-bean");
        if (guard != null && !enabled.contains(guard)) {
            throw new IllegalArgumentException("Guard expert is not enabled for this deployment");
        }
        if (!found) {
            throw new IllegalArgumentException("Selected expert is not enabled for this deployment");
        }
        for (String key : settings.stringPropertyNames()) {
            if (key.startsWith("camel.component.")) {
                main.addProperty(key, settings.getProperty(key));
            }
        }
        main.addProperty("camel.language.semantic.default-expert", selected);
        main.init();
        for (String name : allowed.split(",")) {
            Object bean = main.getCamelContext().getRegistry().lookupByName(name.trim());
            if (bean instanceof CamelContextAware aware) {
                aware.setCamelContext(main.getCamelContext());
            }
            ExpertProperties.bind(main, settings, name.trim(), bean);
            if (bean instanceof Service service) {
                main.getCamelContext().addService(service);
            }
        }
    }
}
