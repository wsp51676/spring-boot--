/*
 * Copyright 2012-2020 the original author or authors.
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

package org.springframework.boot.autoconfigure;

import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.*;

/**
 * Sort {@link EnableAutoConfiguration auto-configuration} classes into priority order by
 * reading {@link AutoConfigureOrder @AutoConfigureOrder},
 * {@link AutoConfigureBefore @AutoConfigureBefore} and
 * {@link AutoConfigureAfter @AutoConfigureAfter} annotations (without loading classes).
 *
 * @author Phillip Webb
 */
class AutoConfigurationSorter {

	private final MetadataReaderFactory metadataReaderFactory;

	private final AutoConfigurationMetadata autoConfigurationMetadata;

	AutoConfigurationSorter(MetadataReaderFactory metadataReaderFactory,
			AutoConfigurationMetadata autoConfigurationMetadata) {
		Assert.notNull(metadataReaderFactory, "MetadataReaderFactory must not be null");
		this.metadataReaderFactory = metadataReaderFactory;
		this.autoConfigurationMetadata = autoConfigurationMetadata;
	}

	List<String> getInPriorityOrder(Collection<String> classNames) {
		AutoConfigurationClasses classes = new AutoConfigurationClasses(this.metadataReaderFactory,
				this.autoConfigurationMetadata, classNames);
		List<String> orderedClassNames = new ArrayList<>(classNames);
		// 先按照字母排序
		// Initially sort alphabetically
		Collections.sort(orderedClassNames);
		// 在按照 Order 排序
		// Then sort by order
		orderedClassNames.sort((o1, o2) -> {
			int i1 = classes.get(o1).getOrder();
			int i2 = classes.get(o2).getOrder();
			return Integer.compare(i1, i2);
		});
		// Then respect @AutoConfigureBefore @AutoConfigureAfter
		orderedClassNames = sortByAnnotation(classes, orderedClassNames);
		// 返回排序后的结果
		return orderedClassNames;
	}

	private List<String> sortByAnnotation(AutoConfigurationClasses classes, List<String> classNames) {
		List<String> toSort = new ArrayList<>(classNames);
		toSort.addAll(classes.getAllNames());
		// 存储排序结果
		Set<String> sorted = new LinkedHashSet<>();
		// 记录正在排序的类 反正出现死循环
		Set<String> processing = new LinkedHashSet<>();
		// 每次都从 toSort 移出元素 进行排序，所以空了就说明排序结束了
		while (!toSort.isEmpty()) {
			doSortByAfterAnnotation(classes, toSort, sorted, processing, null);
		}
		sorted.retainAll(classNames);
		return new ArrayList<>(sorted);
	}

	private void doSortByAfterAnnotation(AutoConfigurationClasses classes, List<String> toSort, Set<String> sorted,
			Set<String> processing, String current) {
		if (current == null) {
			current = toSort.remove(0);
		}
		processing.add(current);
		/**
		 * 获取 current 应该放在 Xx类 的后面，对这些先排序，他们有序了，current 再插入就能保证 current 有序了
		 *
		 * 比如 会得到 A 和 Other
		 * @AutoConfigureAfter(A.class)
		 * class Current{}
		 *
		 * @AutoConfigureBefore(Current.class)
		 * class Other{}
		 * */
		for (String after : classes.getClassesRequestedAfter(current)) {
			// 若出现循环就报错
			checkForCycles(processing, current, after);
			// after 还没排序过，那就递归对其进行排序
			if (!sorted.contains(after) && toSort.contains(after)) {
				// 递归进行排序
				doSortByAfterAnnotation(classes, toSort, sorted, processing, after);
			}
		}
		// 移出标记
		processing.remove(current);
		// 直接插入，因为 sorted 已经插入了应该放在 current 之前的元素，所以这里直接放入就能保证 current 是有序的
		sorted.add(current);
	}

	private void checkForCycles(Set<String> processing, String current, String after) {
		Assert.state(!processing.contains(after),
				() -> "AutoConfigure cycle detected between " + current + " and " + after);
	}

	private static class AutoConfigurationClasses {

		private final Map<String, AutoConfigurationClass> classes = new HashMap<>();

		AutoConfigurationClasses(MetadataReaderFactory metadataReaderFactory,
				AutoConfigurationMetadata autoConfigurationMetadata, Collection<String> classNames) {
			addToClasses(metadataReaderFactory, autoConfigurationMetadata, classNames, true);
		}

		Set<String> getAllNames() {
			return this.classes.keySet();
		}

		private void addToClasses(MetadataReaderFactory metadataReaderFactory,
				AutoConfigurationMetadata autoConfigurationMetadata, Collection<String> classNames, boolean required) {
			for (String className : classNames) {
				if (!this.classes.containsKey(className)) {
					AutoConfigurationClass autoConfigurationClass = new AutoConfigurationClass(className,
							metadataReaderFactory, autoConfigurationMetadata);
					boolean available = autoConfigurationClass.isAvailable();
					if (required || available) {
						this.classes.put(className, autoConfigurationClass);
					}
					if (available) {
						addToClasses(metadataReaderFactory, autoConfigurationMetadata,
								autoConfigurationClass.getBefore(), false);
						addToClasses(metadataReaderFactory, autoConfigurationMetadata,
								autoConfigurationClass.getAfter(), false);
					}
				}
			}
		}

		AutoConfigurationClass get(String className) {
			return this.classes.get(className);
		}

		/**
		 * 获取应该放在 className 之前的配置类。根据 @AutoConfigureAfter、@AutoConfigureBefore 可以知道
		 * @param className
		 * @return
		 */
		Set<String> getClassesRequestedAfter(String className) {
			// 获取 @AutoConfigureAfter 的值。
			Set<String> classesRequestedAfter = new LinkedHashSet<>(get(className).getAfter());
			// 遍历所有的配置类
			this.classes.forEach((name, autoConfigurationClass) -> {
				// 配置类的 @AutoConfigureBefore 的值有 className，说明该配置类应该放在 className 的前面
				if (autoConfigurationClass.getBefore().contains(className)) {
					classesRequestedAfter.add(name);
				}
			});
			return classesRequestedAfter;
		}

	}

	private static class AutoConfigurationClass {

		private final String className;

		private final MetadataReaderFactory metadataReaderFactory;

		private final AutoConfigurationMetadata autoConfigurationMetadata;

		private volatile AnnotationMetadata annotationMetadata;

		private volatile Set<String> before;

		private volatile Set<String> after;

		AutoConfigurationClass(String className, MetadataReaderFactory metadataReaderFactory,
				AutoConfigurationMetadata autoConfigurationMetadata) {
			this.className = className;
			this.metadataReaderFactory = metadataReaderFactory;
			this.autoConfigurationMetadata = autoConfigurationMetadata;
		}

		boolean isAvailable() {
			try {
				if (!wasProcessed()) {
					getAnnotationMetadata();
				}
				return true;
			}
			catch (Exception ex) {
				return false;
			}
		}

		Set<String> getBefore() {
			if (this.before == null) {
				this.before = (wasProcessed() ? this.autoConfigurationMetadata.getSet(this.className,
						"AutoConfigureBefore", Collections.emptySet()) : getAnnotationValue(AutoConfigureBefore.class));
			}
			return this.before;
		}

		Set<String> getAfter() {
			if (this.after == null) {
				this.after = (wasProcessed() ? this.autoConfigurationMetadata.getSet(this.className,
						"AutoConfigureAfter", Collections.emptySet()) : getAnnotationValue(AutoConfigureAfter.class));
			}
			return this.after;
		}

		private int getOrder() {
			if (wasProcessed()) {
				return this.autoConfigurationMetadata.getInteger(this.className, "AutoConfigureOrder",
						AutoConfigureOrder.DEFAULT_ORDER);
			}
			Map<String, Object> attributes = getAnnotationMetadata()
					.getAnnotationAttributes(AutoConfigureOrder.class.getName());
			return (attributes != null) ? (Integer) attributes.get("value") : AutoConfigureOrder.DEFAULT_ORDER;
		}

		private boolean wasProcessed() {
			return (this.autoConfigurationMetadata != null
					&& this.autoConfigurationMetadata.wasProcessed(this.className));
		}

		private Set<String> getAnnotationValue(Class<?> annotation) {
			Map<String, Object> attributes = getAnnotationMetadata().getAnnotationAttributes(annotation.getName(),
					true);
			if (attributes == null) {
				return Collections.emptySet();
			}
			Set<String> value = new LinkedHashSet<>();
			Collections.addAll(value, (String[]) attributes.get("value"));
			Collections.addAll(value, (String[]) attributes.get("name"));
			return value;
		}

		private AnnotationMetadata getAnnotationMetadata() {
			if (this.annotationMetadata == null) {
				try {
					MetadataReader metadataReader = this.metadataReaderFactory.getMetadataReader(this.className);
					this.annotationMetadata = metadataReader.getAnnotationMetadata();
				}
				catch (IOException ex) {
					throw new IllegalStateException("Unable to read meta-data for class " + this.className, ex);
				}
			}
			return this.annotationMetadata;
		}

	}

}
