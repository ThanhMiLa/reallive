UPDATE channels channel
SET follower_count = (
    SELECT COUNT(*)
    FROM channel_followers follower
    WHERE follower.channel_id = channel.id
);
