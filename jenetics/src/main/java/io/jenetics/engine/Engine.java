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
 *    Franz Wilhelmstötter (franz.wilhelmstoetter@gmail.com)
 */
package io.jenetics.engine;

import static java.lang.Math.round;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static io.jenetics.internal.util.require.probability;

import java.time.Clock;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import io.jenetics.Alterer;
import io.jenetics.AltererResult;
import io.jenetics.Chromosome;
import io.jenetics.Gene;
import io.jenetics.Genotype;
import io.jenetics.Mutator;
import io.jenetics.Optimize;
import io.jenetics.Phenotype;
import io.jenetics.Selector;
import io.jenetics.SinglePointCrossover;
import io.jenetics.TournamentSelector;
import io.jenetics.internal.util.require;
import io.jenetics.util.Copyable;
import io.jenetics.util.Factory;
import io.jenetics.util.ISeq;
import io.jenetics.util.MSeq;
import io.jenetics.util.NanoClock;
import io.jenetics.util.Seq;

/**
 * Genetic algorithm <em>engine</em> which is the main class. The following
 * example shows the main steps in initializing and executing the GA.
 *
 * <pre>{@code
 * public class RealFunction {
 *    // Definition of the fitness function.
 *    private static Double eval(final Genotype<DoubleGene> gt) {
 *        final double x = gt.getGene().doubleValue();
 *        return cos(0.5 + sin(x))*cos(x);
 *    }
 *
 *    public static void main(String[] args) {
 *        // Create/configuring the engine via its builder.
 *        final Engine<DoubleGene, Double> engine = Engine
 *            .builder(
 *                RealFunction::eval,
 *                DoubleChromosome.of(0.0, 2.0*PI))
 *            .populationSize(500)
 *            .optimize(Optimize.MINIMUM)
 *            .alterers(
 *                new Mutator<>(0.03),
 *                new MeanAlterer<>(0.6))
 *            .build();
 *
 *        // Execute the GA (engine).
 *        final Phenotype<DoubleGene, Double> result = engine.stream()
 *             // Truncate the evolution stream if no better individual could
 *             // be found after 5 consecutive generations.
 *            .limit(bySteadyFitness(5))
 *             // Terminate the evolution after maximal 100 generations.
 *            .limit(100)
 *            .collect(toBestPhenotype());
 *     }
 * }
 * }</pre>
 *
 * The architecture allows to decouple the configuration of the engine from the
 * execution. The {@code Engine} is configured via the {@code Engine.Builder}
 * class and can't be changed after creation. The actual <i>evolution</i> is
 * performed by the {@link EvolutionStream}, which is created by the
 * {@code Engine}.
 *
 * @implNote
 *     This class is thread safe:
 *     No mutable state is maintained by the engine. Therefore it is save to
 *     create multiple evolution streams with one engine, which may be actually
 *     used in different threads.
 *
 * @see Engine.Builder
 * @see EvolutionStart
 * @see EvolutionResult
 * @see EvolutionStream
 * @see EvolutionStatistics
 * @see Codec
 *
 * @author <a href="mailto:franz.wilhelmstoetter@gmail.com">Franz Wilhelmstötter</a>
 * @since 3.0
 * @version 4.1
 */
@SuppressWarnings("deprecation")
public final class Engine<
	G extends Gene<?, G>,
	C extends Comparable<? super C>
>
	implements
		Function<EvolutionStart<G, C>, EvolutionResult<G, C>>,
		EvolutionStreamable<G, C>,
		EvolutionIterable<G, C>
{

	// Problem definition.
	private final Function<? super Genotype<G>, ? extends C> _fitnessFunction;
	private final Factory<Genotype<G>> _genotypeFactory;

	// Evolution parameters.
	private final Function<? super C, ? extends C> _fitnessScaler;
	private final Selector<G, C> _survivorsSelector;
	private final Selector<G, C> _offspringSelector;
	private final Alterer<G, C> _alterer;
	private final Predicate<? super Phenotype<G, C>> _validator;
	private final Optimize _optimize;
	private final int _offspringCount;
	private final int _survivorsCount;
	private final long _maximalPhenotypeAge;

	// Execution context for concurrent execution of evolving steps.
	private final TimedExecutor _executor;
	private final Evaluator<G, C> _evaluator;
	private final Clock _clock;

	// Additional parameters.
	private final int _individualCreationRetries;
	private final UnaryOperator<EvolutionResult<G, C>> _mapper;


	/**
	 * Create a new GA engine with the given parameters.
	 *
	 * @param fitnessFunction the fitness function this GA is using.
	 * @param genotypeFactory the genotype factory this GA is working with.
	 * @param fitnessScaler the fitness scaler this GA is using.
	 * @param survivorsSelector the selector used for selecting the survivors
	 * @param offspringSelector the selector used for selecting the offspring
	 * @param alterer the alterer used for altering the offspring
	 * @param validator phenotype validator which can override the default
	 *        implementation the {@link Phenotype#isValid()} method.
	 * @param optimize the kind of optimization (minimize or maximize)
	 * @param offspringCount the number of the offspring individuals
	 * @param survivorsCount the number of the survivor individuals
	 * @param maximalPhenotypeAge the maximal age of an individual
	 * @param executor the executor used for executing the single evolve steps
	 * @param evaluator the population fitness evaluator
	 * @param clock the clock used for calculating the timing results
	 * @param individualCreationRetries the maximal number of attempts for
	 *        creating a valid individual.
	 * @throws NullPointerException if one of the arguments is {@code null}
	 * @throws IllegalArgumentException if the given integer values are smaller
	 *         than one.
	 */
	Engine(
		final Function<? super Genotype<G>, ? extends C> fitnessFunction,
		final Factory<Genotype<G>> genotypeFactory,
		final Function<? super C, ? extends C> fitnessScaler,
		final Selector<G, C> survivorsSelector,
		final Selector<G, C> offspringSelector,
		final Alterer<G, C> alterer,
		final Predicate<? super Phenotype<G, C>> validator,
		final Optimize optimize,
		final int offspringCount,
		final int survivorsCount,
		final long maximalPhenotypeAge,
		final Executor executor,
		final Evaluator<G, C> evaluator,
		final Clock clock,
		final int individualCreationRetries,
		final UnaryOperator<EvolutionResult<G, C>> mapper
	) {
		_fitnessFunction = requireNonNull(fitnessFunction);
		_fitnessScaler = requireNonNull(fitnessScaler);
		_genotypeFactory = requireNonNull(genotypeFactory);
		_survivorsSelector = requireNonNull(survivorsSelector);
		_offspringSelector = requireNonNull(offspringSelector);
		_alterer = requireNonNull(alterer);
		_validator = requireNonNull(validator);
		_optimize = requireNonNull(optimize);

		_offspringCount = require.nonNegative(offspringCount);
		_survivorsCount = require.nonNegative(survivorsCount);
		_maximalPhenotypeAge = require.positive(maximalPhenotypeAge);

		_executor = new TimedExecutor(requireNonNull(executor));
		_evaluator = requireNonNull(evaluator);
		_clock = requireNonNull(clock);

		if (individualCreationRetries < 0) {
			throw new IllegalArgumentException(format(
				"Retry count must not be negative: %d",
				individualCreationRetries
			));
		}
		_individualCreationRetries = individualCreationRetries;
		_mapper = requireNonNull(mapper);
	}

	/**
	 * Perform one evolution step with the given {@code population} and
	 * {@code generation}. New phenotypes are created with the fitness function
	 * and fitness scaler defined by this <em>engine</em>
	 * <p>
	 * <em>This method is thread-safe.</em>
	 *
	 * @see #evolve(EvolutionStart)
	 *
	 * @param population the population to evolve
	 * @param generation the current generation; used for calculating the
	 *        phenotype age.
	 * @return the evolution result
	 * @throws java.lang.NullPointerException if the given {@code population} is
	 *         {@code null}
	 * @throws IllegalArgumentException if the given {@code generation} is
	 *         smaller then one
	 */
	public EvolutionResult<G, C> evolve(
		final ISeq<Phenotype<G, C>> population,
		final long generation
	) {
		return evolve(EvolutionStart.of(population, generation));
	}

	/**
	 * Perform one evolution step with the given evolution {@code start} object
	 * New phenotypes are created with the fitness function and fitness scaler
	 * defined by this <em>engine</em>
	 * <p>
	 * <em>This method is thread-safe.</em>
	 *
	 * @since 3.1
	 * @see #evolve(ISeq, long)
	 *
	 * @param start the evolution start object
	 * @return the evolution result
	 * @throws java.lang.NullPointerException if the given evolution
	 *         {@code start} is {@code null}
	 */
	public EvolutionResult<G, C> evolve(final EvolutionStart<G, C> start) {
		final Timer timer = Timer.of(_clock).start();

		// Initial evaluation of the population.
		final Timer evaluateTimer = Timer.of(_clock).start();
		final ISeq<Phenotype<G, C>> evalPop =
			_evaluator.evaluate(start.getPopulation());

		if (start.getPopulation().size() != evalPop.size()) {
			throw new IllegalStateException(format(
				"Expected %d individuals, but got %d. " +
				"Check your evaluator function.",
				start.getPopulation().size(), evalPop.size()
			));
		}

		evaluateTimer.stop();

		// Select the offspring population.
		final CompletableFuture<TimedResult<ISeq<Phenotype<G, C>>>> offspring =
			_executor.async(() ->
				selectOffspring(evalPop),
				_clock
			);

		// Select the survivor population.
		final CompletableFuture<TimedResult<ISeq<Phenotype<G, C>>>> survivors =
			_executor.async(() ->
				selectSurvivors(evalPop),
				_clock
			);

		// Altering the offspring population.
		final CompletableFuture<TimedResult<AltererResult<G, C>>> alteredOffspring =
			_executor.thenApply(offspring, p ->
				_alterer.alter(p.result, start.getGeneration()),
				_clock
			);

		// Filter and replace invalid and old survivor individuals.
		final CompletableFuture<TimedResult<FilterResult<G, C>>> filteredSurvivors =
			_executor.thenApply(survivors, pop ->
				filter(pop.result, start.getGeneration()),
				_clock
			);

		// Filter and replace invalid and old offspring individuals.
		final CompletableFuture<TimedResult<FilterResult<G, C>>> filteredOffspring =
			_executor.thenApply(alteredOffspring, pop ->
				filter(pop.result.getPopulation(), start.getGeneration()),
				_clock
			);

		// Combining survivors and offspring to the new population.
		final CompletableFuture<ISeq<Phenotype<G, C>>> population =
			filteredSurvivors.thenCombineAsync(filteredOffspring, (s, o) ->
					ISeq.of(s.result.population.append(o.result.population)),
				_executor.get()
			);

		// Evaluate the fitness-function and wait for result.
		final ISeq<Phenotype<G, C>> pop = population.join();
		final TimedResult<ISeq<Phenotype<G, C>>> result = TimedResult
			.of(() -> _evaluator.evaluate(pop), _clock)
			.get();


		final EvolutionDurations durations = EvolutionDurations.of(
			offspring.join().duration,
			survivors.join().duration,
			alteredOffspring.join().duration,
			filteredOffspring.join().duration,
			filteredSurvivors.join().duration,
			result.duration.plus(evaluateTimer.getTime()),
			timer.stop().getTime()
		);

		final int killCount =
			filteredOffspring.join().result.killCount +
			filteredSurvivors.join().result.killCount;

		final int invalidCount =
			filteredOffspring.join().result.invalidCount +
			filteredSurvivors.join().result.invalidCount;

		return _mapper.apply(
			EvolutionResult.of(
				_optimize,
				result.result,
				start.getGeneration(),
				durations,
				killCount,
				invalidCount,
				alteredOffspring.join().result.getAlterations()
			)
		);
	}

	/**
	 * This method is an <i>alias</i> for the {@link #evolve(EvolutionStart)}
	 * method.
	 *
	 * @since 3.1
	 */
	@Override
	public EvolutionResult<G, C> apply(final EvolutionStart<G, C> start) {
		return evolve(start);
	}

	// Selects the survivors population. A new population object is returned.
	private ISeq<Phenotype<G, C>>
	selectSurvivors(final ISeq<Phenotype<G, C>> population) {
		return _survivorsCount > 0
			?_survivorsSelector.select(population, _survivorsCount, _optimize)
			: ISeq.empty();
	}

	// Selects the offspring population. A new population object is returned.
	private ISeq<Phenotype<G, C>>
	selectOffspring(final ISeq<Phenotype<G, C>> population) {
		return _offspringCount > 0
			? _offspringSelector.select(population, _offspringCount, _optimize)
			: ISeq.empty();
	}

	// Filters out invalid and old individuals. Filtering is done in place.
	private FilterResult<G, C> filter(
		final Seq<Phenotype<G, C>> population,
		final long generation
	) {
		int killCount = 0;
		int invalidCount = 0;

		final MSeq<Phenotype<G, C>> pop = MSeq.of(population);
		for (int i = 0, n = pop.size(); i < n; ++i) {
			final Phenotype<G, C> individual = pop.get(i);

			if (!_validator.test(individual)) {
				pop.set(i, newPhenotype(generation));
				++invalidCount;
			} else if (individual.getAge(generation) > _maximalPhenotypeAge) {
				pop.set(i, newPhenotype(generation));
				++killCount;
			}
		}

		return new FilterResult<>(pop.toISeq(), killCount, invalidCount);
	}

	// Create a new and valid phenotype
	private Phenotype<G, C> newPhenotype(final long generation) {
		int count = 0;
		Phenotype<G, C> phenotype;
		do {
			phenotype = Phenotype.of(
				_genotypeFactory.newInstance(),
				generation,
				_fitnessFunction,
				_fitnessScaler
			);
		} while (++count < _individualCreationRetries &&
				!_validator.test(phenotype));

		return phenotype;
	}


	/* *************************************************************************
	 * Evolution Stream/Iterator creation.
	 **************************************************************************/

	@Deprecated
	@Override
	public Iterator<EvolutionResult<G, C>>
	iterator(final Supplier<EvolutionStart<G, C>> start) {
		return new EvolutionIterator<>(evolutionStart(start), this::evolve);
	}

	@Deprecated
	@Override
	public Iterator<EvolutionResult<G, C>> iterator(final EvolutionInit<G> init) {
		return iterator(evolutionStart(init));
	}

	@Override
	public EvolutionStream<G, C>
	stream(final Supplier<EvolutionStart<G, C>> start) {
		return EvolutionStream.of(evolutionStart(start), this::evolve);
	}

	@Override
	public EvolutionStream<G, C> stream(final EvolutionInit<G> init) {
		return stream(evolutionStart(init));
	}

	private Supplier<EvolutionStart<G, C>>
	evolutionStart(final Supplier<EvolutionStart<G, C>> start) {
		return () -> {
			final EvolutionStart<G, C> es = start.get();
			final ISeq<Phenotype<G, C>> population = es.getPopulation();
			final long generation = es.getGeneration();

			final Stream<Phenotype<G, C>> stream = Stream.concat(
				population.stream().map(this::toFixedPhenotype),
				Stream.generate(() -> newPhenotype(generation))
			);

			final ISeq<Phenotype<G, C>> pop = stream
				.limit(getPopulationSize())
				.collect(ISeq.toISeq());

			return EvolutionStart.of(pop, generation);
		};
	}

	private Phenotype<G, C> toFixedPhenotype(final Phenotype<G, C> pt) {
		return
			pt.getFitnessFunction() == _fitnessFunction &&
			pt.getFitnessScaler() == _fitnessScaler
				? pt
				: pt.newInstance(
					pt.getGeneration(),
					_fitnessFunction,
					_fitnessScaler
				);
	}

	private Supplier<EvolutionStart<G, C>>
	evolutionStart(final EvolutionInit<G> init) {
		return evolutionStart(() -> EvolutionStart.of(
			init.getPopulation()
				.map(gt -> Phenotype.of(
					gt,
					init.getGeneration(),
					_fitnessFunction,
					_fitnessScaler)
				),
			init.getGeneration())
		);
	}

	/* *************************************************************************
	 * Property access methods.
	 **************************************************************************/

	/**
	 * Return the fitness function of the GA engine.
	 *
	 * @return the fitness function
	 */
	public Function<? super Genotype<G>, ? extends C> getFitnessFunction() {
		return _fitnessFunction;
	}

	/**
	 * Return the fitness scaler of the GA engine.
	 *
	 * @return the fitness scaler
	 *
	 * @deprecated The fitness scaler will be remove in a future version.
	 */
	@Deprecated
	public Function<? super C, ? extends C> getFitnessScaler() {
		return _fitnessScaler;
	}

	/**
	 * Return the used genotype {@link Factory} of the GA. The genotype factory
	 * is used for creating the initial population and new, random individuals
	 * when needed (as replacement for invalid and/or died genotypes).
	 *
	 * @return the used genotype {@link Factory} of the GA.
	 */
	public Factory<Genotype<G>> getGenotypeFactory() {
		return _genotypeFactory;
	}

	/**
	 * Return the used survivor {@link Selector} of the GA.
	 *
	 * @return the used survivor {@link Selector} of the GA.
	 */
	public Selector<G, C> getSurvivorsSelector() {
		return _survivorsSelector;
	}

	/**
	 * Return the used offspring {@link Selector} of the GA.
	 *
	 * @return the used offspring {@link Selector} of the GA.
	 */
	public Selector<G, C> getOffspringSelector() {
		return _offspringSelector;
	}

	/**
	 * Return the used {@link Alterer} of the GA.
	 *
	 * @return the used {@link Alterer} of the GA.
	 */
	public Alterer<G, C> getAlterer() {
		return _alterer;
	}

	/**
	 * Return the number of selected offsprings.
	 *
	 * @return the number of selected offsprings
	 */
	public int getOffspringCount() {
		return _offspringCount;
	}

	/**
	 * The number of selected survivors.
	 *
	 * @return the number of selected survivors
	 */
	public int getSurvivorsCount() {
		return _survivorsCount;
	}

	/**
	 * Return the number of individuals of a population.
	 *
	 * @return the number of individuals of a population
	 */
	public int getPopulationSize() {
		return _offspringCount + _survivorsCount;
	}

	/**
	 * Return the maximal allowed phenotype age.
	 *
	 * @return the maximal allowed phenotype age
	 */
	public long getMaximalPhenotypeAge() {
		return _maximalPhenotypeAge;
	}

	/**
	 * Return the optimization strategy.
	 *
	 * @return the optimization strategy
	 */
	public Optimize getOptimize() {
		return _optimize;
	}

	/**
	 * Return the {@link Clock} the engine is using for measuring the execution
	 * time.
	 *
	 * @return the clock used for measuring the execution time
	 */
	public Clock getClock() {
		return _clock;
	}

	/**
	 * Return the {@link Executor} the engine is using for executing the
	 * evolution steps.
	 *
	 * @return the executor used for performing the evolution steps
	 */
	public Executor getExecutor() {
		return _executor.get();
	}


	/**
	 * Return the maximal number of attempt before the {@code Engine} gives
	 * up creating a valid individual ({@code Phenotype}).
	 *
	 * @since 4.0
	 *
	 * @return the maximal number of {@code Phenotype} creation attempts
	 */
	public int getIndividualCreationRetries() {
		return _individualCreationRetries;
	}

	/**
	 * Return the evolution result mapper.
	 *
	 * @since 4.0
	 *
	 * @return the evolution result mapper
	 */
	public UnaryOperator<EvolutionResult<G, C>> getMapper() {
		return _mapper;
	}

	/* *************************************************************************
	 * Builder methods.
	 **************************************************************************/

	/**
	 * Create a new evolution {@code Engine.Builder} initialized with the values
	 * of the current evolution {@code Engine}. With this method, the evolution
	 * engine can serve as a template for a new one.
	 *
	 * @return a new engine builder
	 */
	public Builder<G, C> builder() {
		return new Builder<G, C>(_genotypeFactory, _fitnessFunction)
			.alterers(_alterer)
			.clock(_clock)
			.evaluator(_evaluator)
			.executor(_executor.get())
			.fitnessScaler(_fitnessScaler)
			.maximalPhenotypeAge(_maximalPhenotypeAge)
			.offspringFraction((double)_offspringCount/(double)getPopulationSize())
			.offspringSelector(_offspringSelector)
			.optimize(_optimize)
			.phenotypeValidator(_validator)
			.populationSize(getPopulationSize())
			.survivorsSelector(_survivorsSelector)
			.individualCreationRetries(_individualCreationRetries)
			.mapping(_mapper);
	}

	/**
	 * Create a new evolution {@code Engine.Builder} for the given
	 * {@link Problem}.
	 *
	 * @since 3.4
	 *
	 * @param problem the problem to be solved by the evolution {@code Engine}
	 * @param <T> the (<i>native</i>) argument type of the problem fitness function
	 * @param <G> the gene type the evolution engine is working with
	 * @param <C> the result type of the fitness function
	 * @return Create a new evolution {@code Engine.Builder}
	 */
	public static <T, G extends Gene<?, G>, C extends Comparable<? super C>>
	Builder<G, C> builder(final Problem<T, G, C> problem) {
		return builder(problem.fitness(), problem.codec());
	}

	/**
	 * Create a new evolution {@code Engine.Builder} with the given fitness
	 * function and genotype factory.
	 *
	 * @param ff the fitness function
	 * @param genotypeFactory the genotype factory
	 * @param <G> the gene type
	 * @param <C> the fitness function result type
	 * @return a new engine builder
	 * @throws java.lang.NullPointerException if one of the arguments is
	 *         {@code null}.
	 */
	public static <G extends Gene<?, G>, C extends Comparable<? super C>>
	Builder<G, C> builder(
		final Function<? super Genotype<G>, ? extends C> ff,
		final Factory<Genotype<G>> genotypeFactory
	) {
		return new Builder<>(genotypeFactory, ff);
	}

	/**
	 * Create a new evolution {@code Engine.Builder} with the given fitness
	 * function and chromosome templates.
	 *
	 * @param ff the fitness function
	 * @param chromosome the first chromosome
	 * @param chromosomes the chromosome templates
	 * @param <G> the gene type
	 * @param <C> the fitness function result type
	 * @return a new engine builder
	 * @throws java.lang.NullPointerException if one of the arguments is
	 *         {@code null}.
	 */
	@SafeVarargs
	public static <G extends Gene<?, G>, C extends Comparable<? super C>>
	Builder<G, C> builder(
		final Function<? super Genotype<G>, ? extends C> ff,
		final Chromosome<G> chromosome,
		final Chromosome<G>... chromosomes
	) {
		return new Builder<>(Genotype.of(chromosome, chromosomes), ff);
	}

	/**
	 * Create a new evolution {@code Engine.Builder} with the given fitness
	 * function and problem {@code codec}.
	 *
	 * @since 3.2
	 *
	 * @param ff the fitness function
	 * @param codec the problem codec
	 * @param <T> the fitness function input type
	 * @param <C> the fitness function result type
	 * @param <G> the gene type
	 * @return a new engine builder
	 * @throws java.lang.NullPointerException if one of the arguments is
	 *         {@code null}.
	 */
	public static <T, G extends Gene<?, G>, C extends Comparable<? super C>>
	Builder<G, C> builder(
		final Function<? super T, ? extends C> ff,
		final Codec<T, G> codec
	) {
		return builder(ff.compose(codec.decoder()), codec.encoding());
	}


	/* *************************************************************************
	 * Inner classes
	 **************************************************************************/


	/**
	 * This interface allows to define different strategies for evaluating the
	 * fitness functions of a given population. <em>Normally</em>, there is no
	 * need for <em>overriding</em> the default evaluation strategy, but it might
	 * be necessary if you have performance problems and a <em>batched</em>
	 * fitness evaluation would solve the problem.
	 * <p>
	 * The implementer is free to do the evaluation <em>in place</em>, or create
	 * new {@link Phenotype} instance and return the newly created one. A simple
	 * serial evaluator can easily implemented:
	 *
	 * <pre>{@code
	 * final Evaluator<G, C> evaluator = population -> {
	 *     population.forEach(Phenotype::evaluate);
	 *     return population.asISeq();
	 * };
	 * }</pre>
	 *
	 * @implSpec
	 * The size of the returned, evaluated, phenotype sequence must be exactly
	 * the size of the input phenotype sequence. It is allowed to return the
	 * input sequence, after evaluation, as well a newly created one.
	 *
	 * @apiNote
	 * This interface is an <em>advanced</em> {@code Engine} configuration
	 * feature, which should be only used when there is a performance gain from
	 * implementing a different evaluation strategy. Another use case is, when
	 * the fitness value of an individual also depends on the current composition
	 * of the population.
	 *
	 * @see GenotypeEvaluator
	 * @see Engine.Builder#evaluator(Engine.Evaluator)
	 *
	 * @param <G> the gene type
	 * @param <C> the fitness result type
	 *
	 * @author <a href="mailto:franz.wilhelmstoetter@gmail.com">Franz Wilhelmstötter</a>
	 * @version 4.2
	 * @since 4.2
	 */
	@FunctionalInterface
	public static interface Evaluator<
		G extends Gene<?, G>,
		C extends Comparable<? super C>
	> {

		/**
		 * Evaluates the fitness values of the given {@code population}. The
		 * given {@code population} might contain already evaluated individuals.
		 * It is the responsibility of the implementer to filter out already
		 * evaluated individuals, if desired.
		 *
		 * @param population the population to evaluate
		 * @return the evaluated population. Implementers are free to return the
		 *         the input population or a newly created one.
		 */
		public ISeq<Phenotype<G, C>> evaluate(final Seq<Phenotype<G, C>> population);

		/**
		 * Create a new phenotype evaluator from a given genotype {@code evaluator}.
		 *
		 * @implNote
		 * The returned {@link Evaluator} will only forward <em>un</em>-evaluated
		 * individuals to the given genotype {@code evaluator}. This means, that
		 * already evaluated individuals are filtered from the population, which
		 * is then forwarded to the underlying genotype {@code evaluator}.
		 *
		 * @param evaluator the genotype evaluator
		 * @param <G> the gene type
		 * @param <C> the fitness result type
		 * @return a <em>normal</em> phenotype evaluator from the given genotype
		 *         evaluator
		 * @throws NullPointerException if the given {@code evaluator} is
		 *         {@code null}
		 */
		public static <G extends Gene<?, G>, C extends Comparable<? super C>>
		Evaluator<G, C> of(final GenotypeEvaluator<G, C> evaluator) {
			requireNonNull(evaluator);

			return population -> {
				final ISeq<Genotype<G>> genotypes = population.stream()
					.filter(pt -> !pt.isEvaluated())
					.map(Phenotype::getGenotype)
					.collect(ISeq.toISeq());

				if (genotypes.nonEmpty()) {
					final ISeq<C> results = evaluator.evaluate(
						genotypes,
						population.get(0).getFitnessFunction()
					);

					if (genotypes.size() != results.size()) {
						throw new IllegalStateException(format(
							"Expected %d results, but got %d. " +
							"Check your evaluator function.",
							genotypes.size(), results.size()
						));
					}

					final MSeq<Phenotype<G, C>> evaluated = population.asMSeq();
					for (int i = 0, j = 0; i < evaluated.length(); ++i) {
						if (!population.get(i).isEvaluated()) {
							evaluated.set(
								i,
								population.get(i).withFitness(results.get(j++))
							);
						}
					}

					return evaluated.toISeq();
				} else {
					return population.asISeq();
				}
			};
		}

	}

	/**
	 * This interface gives a different possibility in evaluating the fitness
	 * values of a population. Sometimes it is necessary (mostly for performance
	 * reason) to calculate the fitness for the whole population at once. This
	 * interface allows you to do so. A simple serial evaluator can easily
	 * implemented:
	 *
	 * <pre>{@code
	 * final GenotypeEvaluator<G, C> gte = (g, f) -> g.map(f).asISeq()
	 * final Evaluator<G, C> evaluator = Evaluator.of(gte);
	 * }</pre>
	 *
	 * @implSpec
	 * The size of the returned result sequence must be exactly the size of the
	 * input genotype sequence.
	 *
	 * @apiNote
	 * This interface is an <em>advanced</em> {@code Engine} configuration
	 * feature, which should be only used when there is a performance gain from
	 * implementing a different evaluation strategy.
	 *
	 * @see Evaluator
	 * @see Engine.Builder#evaluator(Engine.GenotypeEvaluator)
	 *
	 * @author <a href="mailto:franz.wilhelmstoetter@gmail.com">Franz Wilhelmstötter</a>
	 * @version 4.2
	 * @since 4.2
	 */
	@FunctionalInterface
	public static interface GenotypeEvaluator<
		G extends Gene<?, G>,
		C extends Comparable<? super C>
	> {

		/**
		 * Calculate the fitness values for the given sequence of genotypes.
		 *
		 * @see Engine.Evaluator#of(Engine.GenotypeEvaluator)
		 *
		 * @param genotypes the genotypes to evaluate the fitness value for
		 * @param function the fitness function
		 * @return the fitness values for the given {@code genotypes} The length
		 *         of the fitness result sequence must match with the size of
		 *         the given {@code genotypes}.
		 */
		public ISeq<C> evaluate(
			final Seq<Genotype<G>> genotypes,
			final Function<? super Genotype<G>, ? extends C> function
		);

	}


	/**
	 * Builder class for building GA {@code Engine} instances.
	 *
	 * @see Engine
	 *
	 * @author <a href="mailto:franz.wilhelmstoetter@gmail.com">Franz Wilhelmstötter</a>
	 * @since 3.0
	 * @version 4.0
	 */
	public static final class Builder<
		G extends Gene<?, G>,
		C extends Comparable<? super C>
	>
		implements Copyable<Builder<G, C>>
	{

		// No default values for this properties.
		private Function<? super Genotype<G>, ? extends C> _fitnessFunction;
		private Factory<Genotype<G>> _genotypeFactory;

		// This are the properties which default values.
		private Function<? super C, ? extends C> _fitnessScaler = a -> a;
		private Selector<G, C> _survivorsSelector = new TournamentSelector<>(3);
		private Selector<G, C> _offspringSelector = new TournamentSelector<>(3);
		private Alterer<G, C> _alterer = Alterer.of(
			new SinglePointCrossover<G, C>(0.2),
			new Mutator<>(0.15)
		);
		private Predicate<? super Phenotype<G, C>> _validator = Phenotype::isValid;
		private Optimize _optimize = Optimize.MAXIMUM;
		private double _offspringFraction = 0.6;
		private int _populationSize = 50;
		private long _maximalPhenotypeAge = 70;

		// Engine execution environment.
		private Executor _executor = ForkJoinPool.commonPool();
		private Clock _clock = NanoClock.systemUTC();
		private Evaluator<G, C> _evaluator;

		private int _individualCreationRetries = 10;
		private UnaryOperator<EvolutionResult<G, C>> _mapper = r -> r;

		private Builder(
			final Factory<Genotype<G>> genotypeFactory,
			final Function<? super Genotype<G>, ? extends C> fitnessFunction
		) {
			_genotypeFactory = requireNonNull(genotypeFactory);
			_fitnessFunction = requireNonNull(fitnessFunction);
		}

		/**
		 * Set the fitness function of the evolution {@code Engine}.
		 *
		 * @param function the fitness function to use in the GA {@code Engine}
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> fitnessFunction(
			final Function<? super Genotype<G>, ? extends C> function
		) {
			_fitnessFunction = requireNonNull(function);
			return this;
		}

		/**
		 * Set the fitness scaler of the evolution {@code Engine}. <i>Default
		 * value is set to the identity function.</i>
		 *
		 * @param scaler the fitness scale to use in the GA {@code Engine}
		 * @return {@code this} builder, for command chaining
		 *
		 * @deprecated The fitness scaler will be remove in a future version.
		 */
		@Deprecated
		public Builder<G, C> fitnessScaler(
			final Function<? super C, ? extends C> scaler
		) {
			_fitnessScaler = requireNonNull(scaler);
			return this;
		}

		/**
		 * The genotype factory used for creating new individuals.
		 *
		 * @param genotypeFactory the genotype factory for creating new
		 *        individuals.
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> genotypeFactory(
			final Factory<Genotype<G>> genotypeFactory
		) {
			_genotypeFactory = requireNonNull(genotypeFactory);
			return this;
		}

		/**
		 * The selector used for selecting the offspring population. <i>Default
		 * values is set to {@code TournamentSelector<>(3)}.</i>
		 *
		 * @param selector used for selecting the offspring population
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> offspringSelector(
			final Selector<G, C> selector
		) {
			_offspringSelector = requireNonNull(selector);
			return this;
		}

		/**
		 * The selector used for selecting the survivors population. <i>Default
		 * values is set to {@code TournamentSelector<>(3)}.</i>
		 *
		 * @param selector used for selecting survivors population
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> survivorsSelector(
			final Selector<G, C> selector
		) {
			_survivorsSelector = requireNonNull(selector);
			return this;
		}

		/**
		 * The selector used for selecting the survivors and offspring
		 * population. <i>Default values is set to
		 * {@code TournamentSelector<>(3)}.</i>
		 *
		 * @param selector used for selecting survivors and offspring population
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> selector(final Selector<G, C> selector) {
			_offspringSelector = requireNonNull(selector);
			_survivorsSelector = requireNonNull(selector);
			return this;
		}

		/**
		 * The alterers used for alter the offspring population. <i>Default
		 * values is set to {@code new SinglePointCrossover<>(0.2)} followed by
		 * {@code new Mutator<>(0.15)}.</i>
		 *
		 * @param first the first alterer used for alter the offspring
		 *        population
		 * @param rest the rest of the alterers used for alter the offspring
		 *        population
		 * @return {@code this} builder, for command chaining
		 * @throws java.lang.NullPointerException if one of the alterers is
		 *         {@code null}.
		 */
		@SafeVarargs
		public final Builder<G, C> alterers(
			final Alterer<G, C> first,
			final Alterer<G, C>... rest
		) {
			requireNonNull(first);
			Stream.of(rest).forEach(Objects::requireNonNull);

			_alterer = rest.length == 0
				? first
				: Alterer.of(rest).compose(first);

			return this;
		}

		/**
		 * The phenotype validator used for detecting invalid individuals.
		 * Alternatively it is also possible to set the genotype validator with
		 * {@link #genotypeFactory(Factory)}, which will replace any
		 * previously set phenotype validators.
		 *
		 * <p><i>Default value is set to {@code Phenotype::isValid}.</i></p>
		 *
		 * @since 3.1
		 *
		 * @see #genotypeValidator(Predicate)
		 *
		 * @param validator the {@code validator} used for validating the
		 *        individuals (phenotypes).
		 * @return {@code this} builder, for command chaining
		 * @throws java.lang.NullPointerException if the {@code validator} is
		 *         {@code null}.
		 */
		public Builder<G, C> phenotypeValidator(
			final Predicate<? super Phenotype<G, C>> validator
		) {
			_validator = requireNonNull(validator);
			return this;
		}

		/**
		 * The genotype validator used for detecting invalid individuals.
		 * Alternatively it is also possible to set the phenotype validator with
		 * {@link #phenotypeValidator(Predicate)}, which will replace any
		 * previously set genotype validators.
		 *
		 * <p><i>Default value is set to {@code Genotype::isValid}.</i></p>
		 *
		 * @since 3.1
		 *
		 * @see #phenotypeValidator(Predicate)
		 *
		 * @param validator the {@code validator} used for validating the
		 *        individuals (genotypes).
		 * @return {@code this} builder, for command chaining
		 * @throws java.lang.NullPointerException if the {@code validator} is
		 *         {@code null}.
		 */
		public Builder<G, C> genotypeValidator(
			final Predicate<? super Genotype<G>> validator
		) {
			requireNonNull(validator);

			_validator = pt -> validator.test(pt.getGenotype());
			return this;
		}

		/**
		 * The optimization strategy used by the engine. <i>Default values is
		 * set to {@code Optimize.MAXIMUM}.</i>
		 *
		 * @param optimize the optimization strategy used by the engine
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> optimize(final Optimize optimize) {
			_optimize = requireNonNull(optimize);
			return this;
		}

		/**
		 * Set to a fitness maximizing strategy.
		 *
		 * @since 3.4
		 *
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> maximizing() {
			return optimize(Optimize.MAXIMUM);
		}

		/**
		 * Set to a fitness minimizing strategy.
		 *
		 * @since 3.4
		 *
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> minimizing() {
			return optimize(Optimize.MINIMUM);
		}

		/**
		 * The offspring fraction. <i>Default values is set to {@code 0.6}.</i>
		 * This method call is equivalent to
		 * {@code survivorsFraction(1 - offspringFraction)} and will override
		 * any previously set survivors-fraction.
		 *
		 * @see #survivorsFraction(double)
		 *
		 * @param fraction the offspring fraction
		 * @return {@code this} builder, for command chaining
		 * @throws java.lang.IllegalArgumentException if the fraction is not
		 *         within the range [0, 1].
		 */
		public Builder<G, C> offspringFraction(final double fraction) {
			_offspringFraction = probability(fraction);
			return this;
		}

		/**
		 * The survivors fraction. <i>Default values is set to {@code 0.4}.</i>
		 * This method call is equivalent to
		 * {@code offspringFraction(1 - survivorsFraction)} and will override
		 * any previously set offspring-fraction.
		 *
		 * @since 3.8
		 *
		 * @see #offspringFraction(double)
		 *
		 * @param fraction the survivors fraction
		 * @return {@code this} builder, for command chaining
		 * @throws java.lang.IllegalArgumentException if the fraction is not
		 *         within the range [0, 1].
		 */
		public Builder<G, C> survivorsFraction(final double fraction) {
			_offspringFraction = 1.0 - probability(fraction);
			return this;
		}

		/**
		 * The number of offspring individuals.
		 *
		 * @since 3.8
		 *
		 * @param size the number of offspring individuals.
		 * @return {@code this} builder, for command chaining
		 * @throws java.lang.IllegalArgumentException if the size is not
		 *         within the range [0, population-size].
		 */
		public Builder<G, C> offspringSize(final int size) {
			if (size < 0) {
				throw new IllegalArgumentException(format(
					"Offspring size must be greater or equal zero, but was %s.",
					size
				));
			}

			return offspringFraction((double)size/(double)_populationSize);
		}

		/**
		 * The number of survivors.
		 *
		 * @since 3.8
		 *
		 * @param size the number of survivors.
		 * @return {@code this} builder, for command chaining
		 * @throws java.lang.IllegalArgumentException if the size is not
		 *         within the range [0, population-size].
		 */
		public Builder<G, C> survivorsSize(final int size) {
			if (size < 0) {
				throw new IllegalArgumentException(format(
					"Survivors must be greater or equal zero, but was %s.",
					size
				));
			}

			return survivorsFraction((double)size/(double)_populationSize);
		}

		/**
		 * The number of individuals which form the population. <i>Default
		 * values is set to {@code 50}.</i>
		 *
		 * @param size the number of individuals of a population
		 * @return {@code this} builder, for command chaining
		 * @throws java.lang.IllegalArgumentException if {@code size < 1}
		 */
		public Builder<G, C> populationSize(final int size) {
			if (size < 1) {
				throw new IllegalArgumentException(format(
					"Population size must be greater than zero, but was %s.",
					size
				));
			}
			_populationSize = size;
			return this;
		}

		/**
		 * The maximal allowed age of a phenotype. <i>Default values is set to
		 * {@code 70}.</i>
		 *
		 * @param age the maximal phenotype age
		 * @return {@code this} builder, for command chaining
		 * @throws java.lang.IllegalArgumentException if {@code age < 1}
		 */
		public Builder<G, C> maximalPhenotypeAge(final long age) {
			if (age < 1) {
				throw new IllegalArgumentException(format(
					"Phenotype age must be greater than one, but was %s.", age
				));
			}
			_maximalPhenotypeAge = age;
			return this;
		}

		/**
		 * The executor used by the engine.
		 *
		 * @param executor the executor used by the engine
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> executor(final Executor executor) {
			_executor = requireNonNull(executor);
			return this;
		}

		/**
		 * The clock used for calculating the execution durations.
		 *
		 * @param clock the clock used for calculating the execution durations
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> clock(final Clock clock) {
			_clock = requireNonNull(clock);
			return this;
		}

		/**
		 * The phenotype evaluator allows to change the evaluation strategy.
		 * By default, the population is evaluated concurrently using the
		 * defined {@link Executor} implementation.
		 *
		 * @apiNote
		 * This is an <em>advanced</em> {@code Engine} configuration feature,
		 * which should be only used when there is a performance gain from
		 * implementing a different evaluation strategy.
		 *
		 * @since 4.2
		 *
		 * @param evaluator the population evaluation strategy
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> evaluator(final Evaluator<G, C> evaluator) {
			_evaluator = requireNonNull(evaluator);
			return this;
		}

		/**
		 * Setting the <em>genotype</em> evaluator used for evaluating the
		 * fitness function of the population.
		 *
		 * @apiNote
		 * This is an <em>advanced</em> {@code Engine} configuration feature,
		 * which should be only used when there is a performance gain from
		 * implementing a different evaluation strategy.
		 *
		 * @since 4.2
		 *
		 * @param evaluator the genotype evaluator
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> evaluator(final GenotypeEvaluator<G, C> evaluator) {
			_evaluator = Evaluator.of(evaluator);
			return this;
		}

		/**
		 * The maximal number of attempt before the {@code Engine} gives up
		 * creating a valid individual ({@code Phenotype}). <i>Default values is
		 * set to {@code 10}.</i>
		 *
		 * @since 3.1
		 *
		 * @param retries the maximal retry count
		 * @throws IllegalArgumentException if the given retry {@code count} is
		 *         smaller than zero.
		 * @return {@code this} builder, for command chaining
		 */
		public Builder<G, C> individualCreationRetries(final int retries) {
			if (retries < 0) {
				throw new IllegalArgumentException(format(
					"Retry count must not be negative: %d",
					retries
				));
			}
			_individualCreationRetries = retries;
			return this;
		}

		/**
		 * The result mapper, which allows to change the evolution result after
		 * each generation.
		 *
		 * @since 4.0
		 * @see EvolutionResult#toUniquePopulation()
		 *
		 * @param mapper the evolution result mapper
		 * @return {@code this} builder, for command chaining
		 * @throws NullPointerException if the given {@code resultMapper} is
		 *         {@code null}
		 */
		public Builder<G, C> mapping(
			final Function<
				? super EvolutionResult<G, C>,
				EvolutionResult<G, C>
			> mapper
		) {
			_mapper = requireNonNull(mapper::apply);
			return this;
		}

		/**
		 * Builds an new {@code Engine} instance from the set properties.
		 *
		 * @return an new {@code Engine} instance from the set properties
		 */
		public Engine<G, C> build() {
			return new Engine<>(
				_fitnessFunction,
				_genotypeFactory,
				_fitnessScaler,
				_survivorsSelector,
				_offspringSelector,
				_alterer,
				_validator,
				_optimize,
				getOffspringCount(),
				getSurvivorsCount(),
				_maximalPhenotypeAge,
				_executor,
				_evaluator != null
					? _evaluator
					: new ConcurrentEvaluator<>(_executor),
				_clock,
				_individualCreationRetries,
				_mapper
			);
		}

		private int getSurvivorsCount() {
			return _populationSize - getOffspringCount();
		}

		private int getOffspringCount() {
			return (int)round(_offspringFraction*_populationSize);
		}

		/**
		 * Return the used {@link Alterer} of the GA.
		 *
		 * @return the used {@link Alterer} of the GA.
		 */
		public Alterer<G, C> getAlterers() {
			return _alterer;
		}

		/**
		 * Return the {@link Clock} the engine is using for measuring the execution
		 * time.
		 *
		 * @since 3.1
		 *
		 * @return the clock used for measuring the execution time
		 */
		public Clock getClock() {
			return _clock;
		}

		/**
		 * Return the {@link Executor} the engine is using for executing the
		 * evolution steps.
		 *
		 * @since 3.1
		 *
		 * @return the executor used for performing the evolution steps
		 */
		public Executor getExecutor() {
			return _executor;
		}

		/**
		 * Return the fitness function of the GA engine.
		 *
		 * @since 3.1
		 *
		 * @return the fitness function
		 */
		public Function<? super Genotype<G>, ? extends C> getFitnessFunction() {
			return _fitnessFunction;
		}

		/**
		 * Return the fitness scaler of the GA engine.
		 *
		 * @since 3.1
		 *
		 * @return the fitness scaler
		 *
		 * @deprecated The fitness scaler will be remove in a future version.
		 */
		@Deprecated
		public Function<? super C, ? extends C> getFitnessScaler() {
			return _fitnessScaler;
		}

		/**
		 * Return the used genotype {@link Factory} of the GA. The genotype factory
		 * is used for creating the initial population and new, random individuals
		 * when needed (as replacement for invalid and/or died genotypes).
		 *
		 * @since 3.1
		 *
		 * @return the used genotype {@link Factory} of the GA.
		 */
		public Factory<Genotype<G>> getGenotypeFactory() {
			return _genotypeFactory;
		}

		/**
		 * Return the maximal allowed phenotype age.
		 *
		 * @since 3.1
		 *
		 * @return the maximal allowed phenotype age
		 */
		public long getMaximalPhenotypeAge() {
			return _maximalPhenotypeAge;
		}

		/**
		 * Return the offspring fraction.
		 *
		 * @return the offspring fraction.
		 */
		public double getOffspringFraction() {
			return _offspringFraction;
		}

		/**
		 * Return the used offspring {@link Selector} of the GA.
		 *
		 * @since 3.1
		 *
		 * @return the used offspring {@link Selector} of the GA.
		 */
		public Selector<G, C> getOffspringSelector() {
			return _offspringSelector;
		}

		/**
		 * Return the used survivor {@link Selector} of the GA.
		 *
		 * @since 3.1
		 *
		 * @return the used survivor {@link Selector} of the GA.
		 */
		public Selector<G, C> getSurvivorsSelector() {
			return _survivorsSelector;
		}

		/**
		 * Return the optimization strategy.
		 *
		 * @since 3.1
		 *
		 * @return the optimization strategy
		 */
		public Optimize getOptimize() {
			return _optimize;
		}

		/**
		 * Return the number of individuals of a population.
		 *
		 * @since 3.1
		 *
		 * @return the number of individuals of a population
		 */
		public int getPopulationSize() {
			return _populationSize;
		}

		/**
		 * Return the maximal number of attempt before the {@code Engine} gives
		 * up creating a valid individual ({@code Phenotype}).
		 *
		 * @since 3.1
		 *
		 * @return the maximal number of {@code Phenotype} creation attempts
		 */
		public int getIndividualCreationRetries() {
			return _individualCreationRetries;
		}

		/**
		 * Return the evolution result mapper.
		 *
		 * @since 4.0
		 *
		 * @return the evolution result mapper
		 */
		public UnaryOperator<EvolutionResult<G, C>> getMapper() {
			return _mapper;
		}

		/**
		 * Create a new builder, with the current configuration.
		 *
		 * @since 3.1
		 *
		 * @return a new builder, with the current configuration
		 */
		@Override
		public Builder<G, C> copy() {
			return new Builder<G, C>(_genotypeFactory, _fitnessFunction)
				.alterers(_alterer)
				.clock(_clock)
				.executor(_executor)
				.evaluator(_evaluator)
				.fitnessScaler(_fitnessScaler)
				.maximalPhenotypeAge(_maximalPhenotypeAge)
				.offspringFraction(_offspringFraction)
				.offspringSelector(_offspringSelector)
				.phenotypeValidator(_validator)
				.optimize(_optimize)
				.populationSize(_populationSize)
				.survivorsSelector(_survivorsSelector)
				.individualCreationRetries(_individualCreationRetries)
				.mapping(_mapper);
		}

	}

}
