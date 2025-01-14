/*
 * Copyright 2012-2022 the original author or authors.
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

package org.springframework.boot.actuate.autoconfigure.web.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.util.StringUtils;

/**
 * Selects configuration classes for the management context configuration. Entries are
 * loaded from
 * {@code /META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports}.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Moritz Halbritter
 * @see ManagementContextConfiguration
 * @see ImportCandidates
 */
@Order(Ordered.LOWEST_PRECEDENCE)
class ManagementContextConfigurationImportSelector implements DeferredImportSelector, BeanClassLoaderAware {

	private ClassLoader classLoader;

	@Override
	public String[] selectImports(AnnotationMetadata metadata) {
		ManagementContextType contextType = (ManagementContextType) metadata
				.getAnnotationAttributes(EnableManagementContext.class.getName()).get("value");
		// Find all management context configuration classes, filtering duplicates
		List<ManagementConfiguration> configurations = getConfigurations();
		OrderComparator.sort(configurations);
		List<String> names = new ArrayList<>();
		for (ManagementConfiguration configuration : configurations) {
			/**
			 * configuration.getContextType() 是解析配置类上的 @ManagementContextConfiguration 的值 得到的，
			 * 若没有这个注解 会赋值为 ANY
			 * */
			if (configuration.getContextType() == ManagementContextType.ANY
					|| configuration.getContextType() == contextType) {
				names.add(configuration.getClassName());
			}
		}
		/**
		 * 返回，会被作为配置类 注册到BeanFactory中
		 * {@link ConfigurationClassParser#processImports(ConfigurationClass, ConfigurationClassParser.SourceClass, Collection, Predicate, boolean)}
		 * */
		return StringUtils.toStringArray(names);
	}

	private List<ManagementConfiguration> getConfigurations() {
		SimpleMetadataReaderFactory readerFactory = new SimpleMetadataReaderFactory(this.classLoader);
		List<ManagementConfiguration> configurations = new ArrayList<>();
		/**
		 * 会读取 META-INF/spring.factories 和 spring/`ManagementContextConfiguration.class.getName()`.imports 中的内容
		 * */
		for (String className : loadFactoryNames()) {
			addConfiguration(readerFactory, configurations, className);
		}
		return configurations;
	}

	private void addConfiguration(SimpleMetadataReaderFactory readerFactory,
			List<ManagementConfiguration> configurations, String className) {
		try {
			MetadataReader metadataReader = readerFactory.getMetadataReader(className);
			configurations.add(new ManagementConfiguration(metadataReader));
		}
		catch (IOException ex) {
			throw new RuntimeException("Failed to read annotation metadata for '" + className + "'", ex);
		}
	}

	protected List<String> loadFactoryNames() {
		List<String> factoryNames = new ArrayList<>(
				SpringFactoriesLoader.loadFactoryNames(ManagementContextConfiguration.class, this.classLoader));
		ImportCandidates.load(ManagementContextConfiguration.class, this.classLoader).forEach(factoryNames::add);
		return factoryNames;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	/**
	 * A management configuration class which can be sorted according to {@code @Order}.
	 */
	private static final class ManagementConfiguration implements Ordered {

		private final String className;

		private final int order;

		private final ManagementContextType contextType;

		ManagementConfiguration(MetadataReader metadataReader) {
			AnnotationMetadata annotationMetadata = metadataReader.getAnnotationMetadata();
			this.order = readOrder(annotationMetadata);
			this.className = metadataReader.getClassMetadata().getClassName();
			this.contextType = readContextType(annotationMetadata);
		}

		private ManagementContextType readContextType(AnnotationMetadata annotationMetadata) {
			Map<String, Object> annotationAttributes = annotationMetadata
					.getAnnotationAttributes(ManagementContextConfiguration.class.getName());
			return (annotationAttributes != null) ? (ManagementContextType) annotationAttributes.get("value")
					: ManagementContextType.ANY;
		}

		private int readOrder(AnnotationMetadata annotationMetadata) {
			Map<String, Object> attributes = annotationMetadata.getAnnotationAttributes(Order.class.getName());
			Integer order = (attributes != null) ? (Integer) attributes.get("value") : null;
			return (order != null) ? order : Ordered.LOWEST_PRECEDENCE;
		}

		String getClassName() {
			return this.className;
		}

		@Override
		public int getOrder() {
			return this.order;
		}

		ManagementContextType getContextType() {
			return this.contextType;
		}

	}

}
