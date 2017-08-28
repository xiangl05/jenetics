/*
 * Java Genetic Algorithm Library (@__identifier__@).
 * Copyright (c) @__year__@ Franz Wilhelmstötter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author:
 *    Franz Wilhelmstötter (franz.wilhelmstoetter@gmx.at)
 */
package io.jenetics.stat;

import java.util.DoubleSummaryStatistics;
import java.util.Random;
import java.util.stream.IntStream;

import org.testng.annotations.Test;

import io.jenetics.util.Factory;
import io.jenetics.util.ObjectTester;
import io.jenetics.util.RandomRegistry;

/**
 * @author <a href="mailto:franz.wilhelmstoetter@gmx.at">Franz Wilhelmstötter</a>
 */
@Test
public class DoubleSummaryTest extends ObjectTester<DoubleSummary> {

	@Override
	protected Factory<DoubleSummary> factory() {
		return () -> {
			final Random random = RandomRegistry.getRandom();

			final DoubleSummaryStatistics statistics = new DoubleSummaryStatistics();
			IntStream.range(0, 100)
				.mapToDouble(i -> random.nextDouble())
				.forEach(statistics);

			return DoubleSummary.of(statistics);
		};
	}

}
