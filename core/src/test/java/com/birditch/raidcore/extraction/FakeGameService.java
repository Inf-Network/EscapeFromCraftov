package com.birditch.raidcore.extraction;

import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.result.Result;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// Lookup-only [RaidGameService] double: extraction tests exercise only
/// [#find], [#all], [#activeCount] and [#destroyAll]; the game-driving
/// operations fail loudly if touched by accident.
final class FakeGameService implements RaidGameService {

    private final Map<RaidGameId, RaidGame> games = new LinkedHashMap<>();

    void add(RaidGame game) {
        games.put(game.id(), game);
    }

    @Override
    public Optional<RaidGame> find(RaidGameId id) {
        return Optional.ofNullable(games.get(id));
    }

    @Override
    public Collection<RaidGame> all() {
        return Collections.unmodifiableCollection(games.values());
    }

    @Override
    public int activeCount() {
        return (int) games.values().stream().filter(game -> game.state().active()).count();
    }

    @Override
    public void destroyAll() {
        games.clear();
    }

    @Override
    public RaidGame create(RaidMapId mapId) {
        throw new UnsupportedOperationException("create is not supported by FakeGameService");
    }

    @Override
    public Result<RaidGame> join(RaidGameId gameId, UUID playerId) {
        throw new UnsupportedOperationException("join is not supported by FakeGameService");
    }

    @Override
    public Result<RaidGame> leave(UUID playerId) {
        throw new UnsupportedOperationException("leave is not supported by FakeGameService");
    }

    @Override
    public Result<RaidGame> start(RaidGameId id) {
        throw new UnsupportedOperationException("start is not supported by FakeGameService");
    }

    @Override
    public Result<RaidGame> stop(RaidGameId id) {
        throw new UnsupportedOperationException("stop is not supported by FakeGameService");
    }
}
