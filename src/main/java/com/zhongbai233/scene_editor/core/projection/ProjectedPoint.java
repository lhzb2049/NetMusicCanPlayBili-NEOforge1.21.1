package com.zhongbai233.scene_editor.core.projection;

public record ProjectedPoint(double screenX, double screenY, double depth, boolean visible, boolean behindCamera) {
}
