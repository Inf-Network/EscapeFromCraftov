package com.birditch.escapefromcraftov.lobby.party;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// Single-Lobby, main-thread implementation of [PartyService]. Every mutation
/// completes synchronously, so observers never see half-applied membership.
public final class InMemoryPartyService implements PartyService {

  public static final String ALREADY_IN_PARTY = "你已经在一个队伍中";
  public static final String NOT_IN_PARTY = "你当前没有队伍";
  public static final String LEADER_ONLY = "只有队长可以邀请玩家";
  public static final String TARGET_IN_PARTY = "该玩家已经在一个队伍中";
  public static final String PARTY_FULL = "队伍已满（最多 3 人）";
  public static final String NO_INVITATION = "没有可接受的队伍邀请";
  public static final String CANNOT_INVITE_SELF = "不能邀请自己";

  private final Map<UUID, MutableParty> parties = new LinkedHashMap<>();
  private final Map<UUID, UUID> partyByMember = new LinkedHashMap<>();
  private final Map<UUID, UUID> invitationByPlayer = new LinkedHashMap<>();

  @Override
  public Result<PartyView> create(UUID leaderId) {
    Preconditions.checkNotNull(leaderId, "leaderId");
    if (partyByMember.containsKey(leaderId)) return Result.fail(ALREADY_IN_PARTY);
    MutableParty party = new MutableParty(UUID.randomUUID(), leaderId);
    parties.put(party.partyId, party);
    partyByMember.put(leaderId, party.partyId);
    invitationByPlayer.remove(leaderId);
    return Result.ok(party.view());
  }

  @Override
  public Result<PartyView> invite(UUID inviterId, UUID invitedId) {
    Preconditions.checkNotNull(inviterId, "inviterId");
    Preconditions.checkNotNull(invitedId, "invitedId");
    if (inviterId.equals(invitedId)) return Result.fail(CANNOT_INVITE_SELF);
    MutableParty party = mutableParty(inviterId).orElse(null);
    if (party == null) return Result.fail(NOT_IN_PARTY);
    if (!party.leaderId.equals(inviterId)) return Result.fail(LEADER_ONLY);
    if (partyByMember.containsKey(invitedId)) return Result.fail(TARGET_IN_PARTY);
    if (party.members.size() >= MAX_MEMBERS) return Result.fail(PARTY_FULL);
    invitationByPlayer.put(invitedId, party.partyId);
    return Result.ok(party.view());
  }

  @Override
  public Result<PartyView> accept(UUID invitedId) {
    Preconditions.checkNotNull(invitedId, "invitedId");
    if (partyByMember.containsKey(invitedId)) return Result.fail(ALREADY_IN_PARTY);
    UUID partyId = invitationByPlayer.remove(invitedId);
    MutableParty party = partyId == null ? null : parties.get(partyId);
    if (party == null) return Result.fail(NO_INVITATION);
    if (party.members.size() >= MAX_MEMBERS) return Result.fail(PARTY_FULL);
    party.members.add(invitedId);
    partyByMember.put(invitedId, partyId);
    return Result.ok(party.view());
  }

  @Override
  public Result<Optional<PartyView>> leave(UUID memberId) {
    Preconditions.checkNotNull(memberId, "memberId");
    UUID partyId = partyByMember.remove(memberId);
    MutableParty party = partyId == null ? null : parties.get(partyId);
    if (party == null) return Result.fail(NOT_IN_PARTY);
    party.members.remove(memberId);
    invitationByPlayer.remove(memberId);
    if (party.members.isEmpty()) {
      parties.remove(partyId);
      invitationByPlayer.values().removeIf(partyId::equals);
      return Result.ok(Optional.empty());
    }
    if (party.leaderId.equals(memberId)) {
      party.leaderId = party.members.getFirst();
    }
    return Result.ok(Optional.of(party.view()));
  }

  @Override
  public Optional<PartyView> party(UUID memberId) {
    Preconditions.checkNotNull(memberId, "memberId");
    return mutableParty(memberId).map(MutableParty::view);
  }

  @Override
  public Optional<PartyView> partyById(UUID partyId) {
    Preconditions.checkNotNull(partyId, "partyId");
    return Optional.ofNullable(parties.get(partyId)).map(MutableParty::view);
  }

  @Override
  public Optional<UUID> invitingParty(UUID invitedId) {
    Preconditions.checkNotNull(invitedId, "invitedId");
    UUID partyId = invitationByPlayer.get(invitedId);
    return parties.containsKey(partyId) ? Optional.of(partyId) : Optional.empty();
  }

  @Override
  public void disconnect(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    invitationByPlayer.remove(playerId);
    if (partyByMember.containsKey(playerId)) leave(playerId);
  }

  private Optional<MutableParty> mutableParty(UUID memberId) {
    return Optional.ofNullable(partyByMember.get(memberId)).map(parties::get);
  }

  private static final class MutableParty {

    private final UUID partyId;
    private UUID leaderId;
    private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

    private MutableParty(UUID partyId, UUID leaderId) {
      this.partyId = partyId;
      this.leaderId = leaderId;
      members.add(leaderId);
    }

    private PartyView view() {
      return new PartyView(partyId, leaderId, new ArrayList<>(members));
    }
  }
}
