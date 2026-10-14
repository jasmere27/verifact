package com.ai.agent.verifact.intelligence.monitoring;

import java.util.List;

/**
 * One kind of source. A connector returns what it actually retrieved, or throws {@link ConnectorException};
 * it never substitutes made-up items. Content is untrusted.
 */
public interface SourceConnector {

    /** Stable id stored with each mention, e.g. "WEB_SEARCH". */
    String id();

    /** Whether the project has this connector switched on (and configured with anything to fetch). */
    boolean enabledFor(MonitoringConfig config);

    /** Whether the deployment can run it at all (e.g. a search API key is configured). */
    default boolean available() {
        return true;
    }

    List<Item> fetch(Request request);

    /**
     * @param brand   the project's brand
     * @param queries bounded search queries for this run
     */
    record Request(String brand, MonitoringConfig config, List<String> queries) {}

    /**
     * A retrieved item.
     *
     * @param publishedDate as the source reports it, or null
     */
    record Item(String url, String title, String snippet, String publishedDate) {}

    class ConnectorException extends RuntimeException {
        public ConnectorException(String message) {
            super(message);
        }
    }

    /** Thrown with whatever was retrieved when only some of a connector's sources failed. */
    class PartialResult extends RuntimeException {
        private final List<Item> items;

        public PartialResult(List<Item> items, String message) {
            super(message);
            this.items = List.copyOf(items);
        }

        public List<Item> items() {
            return items;
        }
    }
}
