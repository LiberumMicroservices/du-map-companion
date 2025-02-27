package de.tiramon.du.map;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import bell.oauth.discord.domain.User;
import bell.oauth.discord.main.OAuthBuilder;
import de.tiramon.du.map.service.Service;

public class InstanceProvider {
	protected static Logger log = LoggerFactory.getLogger(InstanceProvider.class);

	private static Gson gson = new Gson();
	private static Properties properties = initProperties();
	private static OAuthBuilder oauthbuilder = oauthBuilder();
	private static Service service = new Service();

	private static Properties initProperties() {
		Properties properties = new Properties();
		File file = new File("application.properties");
		if (file.exists() && file.canRead()) {
			log.info("Found properties file");
			try (FileInputStream propertiesInput = new FileInputStream(file)) {
				properties.load(propertiesInput);
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		return properties;
	}

	private static OAuthBuilder oauthBuilder() {
		OAuthBuilder builder = new OAuthBuilder("780864362234511400", "Tk1Ni6x6wm239aN2juHh3o90glPusCqB").setScopes(new String[] { "identify" }).setRedirectURI("http://localhost:4201/");
		String accessToken = InstanceProvider.getProperties().getProperty("access");

		if (!StringUtils.isEmpty(accessToken)) {
			log.info("Found access token '{}' in properties", accessToken);

			builder.setAccess_token(accessToken);
			try {
				User user = builder.getUser();
			} catch (JSONException e) {
				builder.setAccess_token(null);
				log.info("Could not use autologin '{}'", accessToken);
			}

		}
		return builder;
	}

	public static OAuthBuilder getOAuthBuilder() {
		return oauthbuilder;
	}

	public static Properties getProperties() {
		return properties;
	}

	public static Gson getGson() {
		return gson;
	}

	public static Service getService() {
		return service;
	}
}
