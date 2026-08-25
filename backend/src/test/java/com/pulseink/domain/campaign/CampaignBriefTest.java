package com.pulseink.domain.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CampaignBriefTest {

    @Test
    void briefKeepsAnImmutableSnapshotOfChannelsAndConstraints() {
        var channels = new ArrayList<>(List.of(CampaignChannel.BLOG));
        var constraints = new ArrayList<>(List.of("cite factual claims"));

        var brief = new CampaignBrief(
                "Launch PulseInk",
                "Java developers",
                channels,
                constraints);
        channels.add(CampaignChannel.SOCIAL);
        constraints.clear();

        assertThat(brief.channels()).containsExactly(CampaignChannel.BLOG);
        assertThat(brief.constraints()).containsExactly("cite factual claims");
    }

    @Test
    void blankObjectiveIsRejected() {
        assertThatThrownBy(() -> new CampaignBrief(
                        "  ",
                        "Java developers",
                        List.of(CampaignChannel.BLOG),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign objective must not be blank");
    }

    @Test
    void blankAudienceIsRejected() {
        assertThatThrownBy(() -> new CampaignBrief(
                        "Launch PulseInk",
                        " ",
                        List.of(CampaignChannel.BLOG),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign audience must not be blank");
    }

    @Test
    void emptyChannelListIsRejected() {
        assertThatThrownBy(() -> new CampaignBrief(
                        "Launch PulseInk",
                        "Java developers",
                        List.of(),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign must target at least one channel");
    }

    @Test
    void duplicateChannelsAreRejected() {
        assertThatThrownBy(() -> new CampaignBrief(
                        "Launch PulseInk",
                        "Java developers",
                        List.of(CampaignChannel.BLOG, CampaignChannel.BLOG),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign channels must not contain duplicates");
    }

    @Test
    void moreThanTwentyConstraintsAreRejected() {
        var constraints = new ArrayList<String>();
        for (int i = 0; i < 21; i++) {
            constraints.add("constraint " + i);
        }

        assertThatThrownBy(() -> new CampaignBrief(
                        "Launch PulseInk",
                        "Java developers",
                        List.of(CampaignChannel.BLOG),
                        constraints))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign must define at most 20 constraints");
    }

    @Test
    void blankConstraintIsRejected() {
        assertThatThrownBy(() -> new CampaignBrief(
                        "Launch PulseInk",
                        "Java developers",
                        List.of(CampaignChannel.BLOG),
                        List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign constraints must not be blank");
    }
}
