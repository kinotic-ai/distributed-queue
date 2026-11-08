package org.kinotic.queue.api.model;

/**
 * A record leased to one worker of a group.
 *
 * @param record        the record
 * @param deliveryCount how many times the shard's current owner has leased the record to the group's workers,
 *                      including this time; a change of owner starts the count again
 */
public record WorkItem(QueueRecord record, int deliveryCount) {
}
