/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.autoconfigure.endpoint.web.servlet;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.EndpointExposure;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.CorsEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.endpoint.ExposableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.web.*;
import org.springframework.boot.actuate.endpoint.web.annotation.ControllerEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.annotation.ServletEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.servlet.AbstractWebMvcEndpointHandlerMapping;
import org.springframework.boot.actuate.endpoint.web.servlet.AdditionalHealthEndpointPathsWebMvcHandlerMapping;
import org.springframework.boot.actuate.endpoint.web.servlet.ControllerEndpointHandlerMapping;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link ManagementContextConfiguration @ManagementContextConfiguration} for Spring MVC
 * {@link Endpoint @Endpoint} concerns.
 *
 * @author Andy Wilkinson
 * @author Phillip Webb
 * @since 2.0.0
 */
@ManagementContextConfiguration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnBean({ DispatcherServlet.class, WebEndpointsSupplier.class })
@EnableConfigurationProperties(CorsEndpointProperties.class)
public class WebMvcEndpointManagementContextConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public WebMvcEndpointHandlerMapping webEndpointServletHandlerMapping(WebEndpointsSupplier webEndpointsSupplier,
			ServletEndpointsSupplier servletEndpointsSupplier, ControllerEndpointsSupplier controllerEndpointsSupplier,
			EndpointMediaTypes endpointMediaTypes, CorsEndpointProperties corsProperties,
			WebEndpointProperties webEndpointProperties, Environment environment) {
		List<ExposableEndpoint<?>> allEndpoints = new ArrayList<>();
		/**
		 * 从容器中找到 @Endpoint 的bean，并且会解析bean里面的 @ReadOperation 、@WriteOperation 、@DeleteOperation 方法
		 * */
		Collection<ExposableWebEndpoint> webEndpoints = webEndpointsSupplier.getEndpoints();
		allEndpoints.addAll(webEndpoints);
		/**
		 * 从容器中找到 @ServletEndpoint 的bean
		 * */
		allEndpoints.addAll(servletEndpointsSupplier.getEndpoints());
		/**
		 * 从容器中找到 @ControllerEndpoint @RestControllerEndpoint 的bean
		 * */
		allEndpoints.addAll(controllerEndpointsSupplier.getEndpoints());
		// 可以通过 management.endpoints.web.basePath 设置值，默认是 /actuator
		String basePath = webEndpointProperties.getBasePath();
		// endpointMapping 是用来统一前缀的，构造 映射路径时会使用 basePath 拼接而成
		EndpointMapping endpointMapping = new EndpointMapping(basePath);
		// 可以通过 management.endpoints.web.discovery.enabled 设置值，默认是 true
		boolean shouldRegisterLinksMapping = shouldRegisterLinksMapping(webEndpointProperties, environment, basePath);
		/**
		 * {@link AbstractWebMvcEndpointHandlerMapping#initHandlerMethods()}
		 * 		只会处理有 @Endpoint 的bean，并会解析bean里面的 @ReadOperation 、@WriteOperation 、@DeleteOperation 方法
		 * 		注册到 {@link AbstractHandlerMethodMapping#mappingRegistry}
		 *
		 * 	至于 controllerEndpointsSupplier 的真正处理看 {@link WebMvcEndpointManagementContextConfiguration#controllerEndpointHandlerMapping(ControllerEndpointsSupplier, CorsEndpointProperties, WebEndpointProperties)}
		 *
		 * 	多传入 servletEndpointsSupplier、controllerEndpointsSupplier 是为了记录这些端点的信息，然后可以访问 /actuator 查看到
		 * */
		return new WebMvcEndpointHandlerMapping(endpointMapping, webEndpoints, endpointMediaTypes,
				corsProperties.toCorsConfiguration(), new EndpointLinksResolver(allEndpoints, basePath),
				shouldRegisterLinksMapping, WebMvcAutoConfiguration.pathPatternParser);
	}

	private boolean shouldRegisterLinksMapping(WebEndpointProperties webEndpointProperties, Environment environment,
			String basePath) {
		return webEndpointProperties.getDiscovery().isEnabled() && (StringUtils.hasText(basePath)
				|| ManagementPortType.get(environment).equals(ManagementPortType.DIFFERENT));
	}

	@Bean
	@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
	@ConditionalOnBean(HealthEndpoint.class)
	@ConditionalOnAvailableEndpoint(endpoint = HealthEndpoint.class, exposure = EndpointExposure.WEB)
	public AdditionalHealthEndpointPathsWebMvcHandlerMapping managementHealthEndpointWebMvcHandlerMapping(
			WebEndpointsSupplier webEndpointsSupplier, HealthEndpointGroups groups) {
		Collection<ExposableWebEndpoint> webEndpoints = webEndpointsSupplier.getEndpoints();
		// 过滤出是 health 端点
		ExposableWebEndpoint health = webEndpoints.stream()
				.filter((endpoint) -> endpoint.getEndpointId().equals(HealthEndpoint.ID)).findFirst().get();
		/**
		 *  若 group 设置了 additionalPath，那就注册映射关系
		 * 	比如设置了属性：management.endpoint.health.group.custom.additionalPath=server:/health-custom
		 * */
		return new AdditionalHealthEndpointPathsWebMvcHandlerMapping(health,
				groups.getAllWithAdditionalPath(WebServerNamespace.MANAGEMENT));
	}

	@Bean
	@ConditionalOnMissingBean
	public ControllerEndpointHandlerMapping controllerEndpointHandlerMapping(
			ControllerEndpointsSupplier controllerEndpointsSupplier, CorsEndpointProperties corsProperties,
			WebEndpointProperties webEndpointProperties) {
		EndpointMapping endpointMapping = new EndpointMapping(webEndpointProperties.getBasePath());
		/**
		 * controllerEndpointsSupplier 是 从容器中找到 @ControllerEndpoint @RestControllerEndpoint 的bean
		 *
		 * ControllerEndpointHandlerMapping 的作用是 找到bean中的 @RequestMapping 然后注册映射关系
		 * */
		return new ControllerEndpointHandlerMapping(endpointMapping, controllerEndpointsSupplier.getEndpoints(),
				corsProperties.toCorsConfiguration());
	}

}
