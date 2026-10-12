package dev.villageroads.client;

import dev.villageroads.net.RoadPayloads;
import net.minecraft.client.Minecraft;

/** The client's side of the staff (loaded on the client only). */
public final class RoadsClient {
    private RoadsClient() {}

    public static void openSurvey(RoadPayloads.OpenSurvey p) {
        Minecraft.getInstance().setScreen(new SurveyScreen(p));
    }
}
