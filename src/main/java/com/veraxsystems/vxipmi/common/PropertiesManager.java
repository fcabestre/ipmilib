package com.veraxsystems.vxipmi.common;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class PropertiesManager {

    private static PropertiesManager instance;

    private Map<String, String> properties;

    private Logger logger = Logger.getLogger(PropertiesManager.class);

    private PropertiesManager() {
        properties = new HashMap<String, String>();

        loadProperties("/connection.properties");
        loadProperties("/vxipmi.properties");
    }

    public static PropertiesManager getInstance() {
        if (instance == null) {
            instance = new PropertiesManager();
        }
        return instance;
    }

    private void loadProperties(String name) {
        try {
            Properties properties = new Properties();
            properties.load(getClass().getResourceAsStream(name));

            for (Object key : properties.keySet()) {
                this.properties.put(key.toString(), properties.getProperty(key.toString()));
            }

        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public String getProperty(String key) {
        logger.info("Getting " + key + ": " + properties.get(key));
        return properties.get(key);
    }

    public void setProperty(String key, String value) {
        properties.put(key, value);
    }
}
