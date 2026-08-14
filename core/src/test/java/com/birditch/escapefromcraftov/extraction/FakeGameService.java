package com.birditch.escapefromcraftov.extraction;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// Lookup-only [CraftovGameService] double: extraction tests exercise only
/// [#find], [#all], [#activeCount] and [#destroyAll]; the game-driving
/// operations fail loudly if touched by accident.
final class FakeGameService implements CraftovGameService {

    private final Map<CraftovGameId, CraftovGame> games = new LinkedHashMap<>();

    void add(CraftovGame game) {
        games.put(game.id(), game);
    }

    @Override
    public Optional<CraftovGame> find(CraftovGameId id) {
        return Optional.ofNullable(games.get(id));
    }

    @Override
    public Collection<CraftovGame> all() {
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
    public Result<CraftovGame> create(CraftovMapId mapId) {
        throw new UnsupportedOperationException("create is not supported by FakeGameService");
    }

    @Override
    public Result<CraftovGame> join(CraftovGameId gameId, UUID playerId) {
        throw new UnsupportedOperationException("join is not supported by FakeGameService");
    }

    @Override
    public Result<CraftovGame> leave(UUID playerId) {
        throw new UnsupportedOperationException("leave is not supported by FakeGameService");
    }

    @Override
    public Result<CraftovGame> start(CraftovGameId id) {
        throw new UnsupportedOperationException("start is not supported by FakeGameService");
    }

    @Override
    public Result<CraftovGame> stop(CraftovGameId id) {
        throw new UnsupportedOperationException("stop is not supported by FakeGameService");
    }
}
