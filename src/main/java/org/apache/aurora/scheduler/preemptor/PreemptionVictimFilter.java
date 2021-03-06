/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.preemptor;

import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import org.apache.aurora.scheduler.HostOffer;
import org.apache.aurora.scheduler.TierManager;
import org.apache.aurora.scheduler.configuration.executor.ExecutorSettings;
import org.apache.aurora.scheduler.filter.AttributeAggregate;
import org.apache.aurora.scheduler.filter.SchedulingFilter;
import org.apache.aurora.scheduler.filter.SchedulingFilter.ResourceRequest;
import org.apache.aurora.scheduler.filter.SchedulingFilter.UnusedResource;
import org.apache.aurora.scheduler.filter.SchedulingFilter.Veto;
import org.apache.aurora.scheduler.resources.ResourceBag;
import org.apache.aurora.scheduler.resources.ResourceManager;
import org.apache.aurora.scheduler.storage.Storage.StoreProvider;
import org.apache.aurora.scheduler.storage.entities.IHostAttributes;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;

import static java.util.Objects.requireNonNull;

import static org.apache.aurora.scheduler.resources.ResourceBag.EMPTY;
import static org.apache.aurora.scheduler.resources.ResourceBag.IS_MESOS_REVOCABLE;
import static org.apache.aurora.scheduler.resources.ResourceManager.bagFromMesosResources;
import static org.apache.aurora.scheduler.resources.ResourceManager.getNonRevocableOfferResources;

/**
 * Filters active tasks (victims) and available offer (slack) resources that can accommodate a
 * given task (candidate), provided victims are preempted.
 * <p>
 * A task may preempt another task if the following conditions hold true:
 * <ol>
 *  <li>The resources reserved for a victim (or a set of victims) are sufficient to satisfy
 *    the candidate.
 *  </li>
 *  <li>Both candidate and victim are owned by the same user and the
 *    {@link ITaskConfig#getPriority} of a victim is lower OR a victim is non-production and the
 *    candidate is production.
 *  </li>
 * </ol>
 */
public interface PreemptionVictimFilter {
  /**
   * Returns a set of {@link PreemptionVictim} that can accommodate a given task if preempted.
   *
   * @param pendingTask Task to search preemption slot for.
   * @param victims Active tasks on a slave.
   * @param attributeAggregate An {@link AttributeAggregate} instance for the task's job.
   * @param offer A resource offer for a slave.
   * @param storeProvider A store provider to access task data.
   * @return A set of {@code PreemptionVictim} instances to preempt for a given task.
   */
  Optional<ImmutableSet<PreemptionVictim>> filterPreemptionVictims(
      ITaskConfig pendingTask,
      Iterable<PreemptionVictim> victims,
      AttributeAggregate attributeAggregate,
      Optional<HostOffer> offer,
      StoreProvider storeProvider);

  class PreemptionVictimFilterImpl implements PreemptionVictimFilter {
    private final SchedulingFilter schedulingFilter;
    private final ExecutorSettings executorSettings;
    private final PreemptorMetrics metrics;
    private final TierManager tierManager;

    @Inject
    PreemptionVictimFilterImpl(
        SchedulingFilter schedulingFilter,
        ExecutorSettings executorSettings,
        PreemptorMetrics metrics,
        TierManager tierManager) {

      this.schedulingFilter = requireNonNull(schedulingFilter);
      this.executorSettings = requireNonNull(executorSettings);
      this.metrics = requireNonNull(metrics);
      this.tierManager = requireNonNull(tierManager);
    }

    private static final Function<HostOffer, String> OFFER_TO_HOST =
        offer -> offer.getOffer().getHostname();

    private static final Function<PreemptionVictim, String> VICTIM_TO_HOST =
        PreemptionVictim::getSlaveHost;

    private final Function<PreemptionVictim, ResourceBag> victimToResources =
        new Function<PreemptionVictim, ResourceBag>() {
          @Override
          public ResourceBag apply(PreemptionVictim victim) {
            ResourceBag bag = victim.getResourceBag();
            if (tierManager.getTier(victim.getConfig()).isRevocable()) {
              // Revocable task CPU cannot be used for preemption purposes as it's a compressible
              // resource. We can still use RAM, DISK and PORTS as they are not compressible.
              bag = bag.filter(IS_MESOS_REVOCABLE.negate());
            }
            return bag.add(executorSettings.getExecutorOverhead());
          }
        };

    private static final java.util.function.Predicate<Integer> IS_ZERO = e -> e == 0;

    /**
     * A Resources object is greater than another iff _all_ of its resource components are greater.
     * A Resources object compares as equal if some but not all components are greater
     * than or equal to the other.
     */
    @VisibleForTesting
    static final Ordering<ResourceBag> ORDER = new Ordering<ResourceBag>() {
      @Override
      public int compare(ResourceBag left, ResourceBag right) {
        ImmutableList.Builder<Integer> builder = ImmutableList.builder();
        left.streamResourceVectors().forEach(
            entry -> builder.add(entry.getValue().compareTo(right.valueOf(entry.getKey()))));

        List<Integer> results = builder.build();

        if (results.stream().allMatch(IS_ZERO))  {
          return 0;
        }

        if (results.stream().filter(IS_ZERO.negate()).allMatch(e -> e > 0)) {
          return 1;
        }

        if (results.stream().filter(IS_ZERO.negate()).allMatch(e -> e < 0)) {
          return -1;
        }

        return 0;
      }
    };

    // TODO(zmanji) Consider using Dominant Resource Fairness for ordering instead of the vector
    // ordering
    private final Ordering<PreemptionVictim> resourceOrder =
        ORDER.onResultOf(victimToResources).reverse();

    @Override
    public Optional<ImmutableSet<PreemptionVictim>> filterPreemptionVictims(
        ITaskConfig pendingTask,
        Iterable<PreemptionVictim> possibleVictims,
        AttributeAggregate jobState,
        Optional<HostOffer> offer,
        StoreProvider storeProvider) {

      // This enforces the precondition that all of the resources are from the same host. We need to
      // get the host for the schedulingFilter.
      Set<String> hosts = ImmutableSet.<String>builder()
          .addAll(Iterables.transform(possibleVictims, VICTIM_TO_HOST))
          .addAll(Iterables.transform(offer.asSet(), OFFER_TO_HOST)).build();

      ResourceBag slackResources = offer.asSet().stream()
          .map(o -> bagFromMesosResources(getNonRevocableOfferResources(o.getOffer())))
          .reduce((l, r) -> l.add(r))
          .orElse(EMPTY);

      FluentIterable<PreemptionVictim> preemptableTasks = FluentIterable.from(possibleVictims)
          .filter(preemptionFilter(pendingTask));

      List<PreemptionVictim> sortedVictims = resourceOrder.immutableSortedCopy(preemptableTasks);
      if (sortedVictims.isEmpty()) {
        return Optional.absent();
      }

      Set<PreemptionVictim> toPreemptTasks = Sets.newHashSet();

      Optional<IHostAttributes> attributes =
          storeProvider.getAttributeStore().getHostAttributes(Iterables.getOnlyElement(hosts));

      if (!attributes.isPresent()) {
        metrics.recordMissingAttributes();
        return Optional.absent();
      }

      ResourceBag totalResource = slackResources;
      for (PreemptionVictim victim : sortedVictims) {
        toPreemptTasks.add(victim);
        totalResource = totalResource.add(victimToResources.apply(victim));
        Set<Veto> vetoes = schedulingFilter.filter(
            new UnusedResource(totalResource, attributes.get()),
            new ResourceRequest(
                pendingTask,
                ResourceManager.bagFromResources(pendingTask.getResources())
                    .add(executorSettings.getExecutorOverhead()),
                jobState));

        if (vetoes.isEmpty()) {
          return Optional.of(ImmutableSet.copyOf(toPreemptTasks));
        }
      }
      return Optional.absent();
    }

    /**
     * Creates a filter that will find tasks that the provided {@code pendingTask} may preempt.
     *
     * @param pendingTask A task that is not scheduled to possibly preempt other tasks for.
     * @return A filter that will compare the priorities and resources required by other tasks
     *     with {@code preemptableTask}.
     */
    private Predicate<PreemptionVictim> preemptionFilter(final ITaskConfig pendingTask) {
      return possibleVictim -> {
        boolean pendingIsPreemptible = tierManager.getTier(pendingTask).isPreemptible();
        boolean victimIsPreemptible =
            tierManager.getTier(possibleVictim.getConfig()).isPreemptible();

        if (!pendingIsPreemptible && victimIsPreemptible) {
          return true;
        } else if (pendingIsPreemptible == victimIsPreemptible) {
          // If preemptible flags are equal, preemption is based on priority within the same role.
          if (pendingTask.getJob().getRole().equals(possibleVictim.getRole())) {
            return pendingTask.getPriority() > possibleVictim.getPriority();
          } else {
            return false;
          }
        } else {
          return false;
        }
      };
    }
  }
}
