package com.birditch.escapefromcraftov.game.plugin.editor;

public final class MapRevisionConflictException extends IllegalStateException {

  public MapRevisionConflictException(String mapId, long expected, long actual) {
    super("地图 " + mapId + " 已被其他管理员保存；编辑版本 " + expected
        + "，当前版本 " + actual + "。请退出后重新进入编辑器。");
  }
}
