/*
 * Copyright 2012-2019 the original author or authors.
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

package org.springframework.boot.autoconfigure.condition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

/**
 * Abstract base class for a {@link SpringBootCondition} that also implements
 * {@link AutoConfigurationImportFilter}.
 *
 * @author Phillip Webb
 */
abstract class FilteringSpringBootCondition extends SpringBootCondition
		implements AutoConfigurationImportFilter, BeanFactoryAware, BeanClassLoaderAware {

	private BeanFactory beanFactory;

	private ClassLoader beanClassLoader;

	/**
	 * 这个方法是在获取自动配置类后，要对配置类进行过滤，会用到的。并不是 @Conditional 的校验会执行
	 * 		{@link AutoConfigurationImportSelector#getAutoConfigurationEntry(AnnotationMetadata)}
	 *
	 * @param autoConfigurationClasses the auto-configuration classes being considered.
	 * This array may contain {@code null} elements. Implementations should not change the
	 * values in this array.
	 * @param autoConfigurationMetadata access to the meta-data generated by the
	 * auto-configure annotation processor
	 * @return
	 */
	@Override
	public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
		/**
		 * 从 BeanFactory 中拿到 ConditionEvaluationReport 这个bean，拿不到就new 然后设置到容器中
		 *
		 * new ConditionEvaluationReport();
		 * */
		ConditionEvaluationReport report = ConditionEvaluationReport.find(this.beanFactory);

		// 抽象方法，看具体子类如何实现的
		ConditionOutcome[] outcomes = getOutcomes(autoConfigurationClasses, autoConfigurationMetadata);
		boolean[] match = new boolean[outcomes.length];
		for (int i = 0; i < outcomes.length; i++) {
			// 是匹配
			match[i] = (outcomes[i] == null || outcomes[i].isMatch());
			// 不匹配就记录起来
			if (!match[i] && outcomes[i] != null) {
				logOutcome(autoConfigurationClasses[i], outcomes[i]);
				if (report != null) {
					report.recordConditionEvaluation(autoConfigurationClasses[i], this, outcomes[i]);
				}
			}
		}
		return match;
	}

	protected abstract ConditionOutcome[] getOutcomes(String[] autoConfigurationClasses,
			AutoConfigurationMetadata autoConfigurationMetadata);

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	protected final BeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	protected final ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	protected final List<String> filter(Collection<String> classNames, ClassNameFilter classNameFilter,
			ClassLoader classLoader) {
		if (CollectionUtils.isEmpty(classNames)) {
			return Collections.emptyList();
		}
		List<String> matches = new ArrayList<>(classNames.size());
		for (String candidate : classNames) {
			if (classNameFilter.matches(candidate, classLoader)) {
				matches.add(candidate);
			}
		}
		return matches;
	}

	/**
	 * Slightly faster variant of {@link ClassUtils#forName(String, ClassLoader)} that
	 * doesn't deal with primitives, arrays or inner types.
	 * @param className the class name to resolve
	 * @param classLoader the class loader to use
	 * @return a resolved class
	 * @throws ClassNotFoundException if the class cannot be found
	 */
	protected static Class<?> resolve(String className, ClassLoader classLoader) throws ClassNotFoundException {
		if (classLoader != null) {
			return Class.forName(className, false, classLoader);
		}
		return Class.forName(className);
	}

	protected enum ClassNameFilter {

		PRESENT {

			@Override
			public boolean matches(String className, ClassLoader classLoader) {
				return isPresent(className, classLoader);
			}

		},

		MISSING {

			@Override
			public boolean matches(String className, ClassLoader classLoader) {
				return !isPresent(className, classLoader);
			}

		};

		abstract boolean matches(String className, ClassLoader classLoader);

		static boolean isPresent(String className, ClassLoader classLoader) {
			if (classLoader == null) {
				classLoader = ClassUtils.getDefaultClassLoader();
			}
			try {
				resolve(className, classLoader);
				return true;
			}
			catch (Throwable ex) {
				return false;
			}
		}

	}

}
