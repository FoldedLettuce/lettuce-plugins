package xyz.foldedlettuce.lettucegames;

/** Manhunt / Hitman sides. Runners are the Green team in game, Hunters are Red. */
public enum Role {
    RUNNER(TeamColor.GREEN), HUNTER(TeamColor.RED);

    public final TeamColor team;

    Role(TeamColor team) {
        this.team = team;
    }

    public String display(Mode m) {
        return this == RUNNER ? m.runner() : m.hunter();
    }

    public String plural(Mode m) {
        return this == RUNNER ? m.runners() : m.hunters();
    }

    public String code() {
        return team.code;
    }
}
