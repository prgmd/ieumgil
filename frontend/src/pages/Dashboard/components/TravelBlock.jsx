const CATEGORY_UI = {
  SPOT: { icon: "🚩", color: "#ffebee" },
  FOOD: { icon: "🍽️", color: "#fff3e0" },
  STAY: { icon: "🛏️", color: "#e3f2fd" },
  ETC: { icon: "✨", color: "#f3e5f5" },
  TRANSPORT: { icon: "🚌", color: "#e8f5e9" },
};

export function TravelBlock({ blockData, onClickEdit }) {
  const ui = CATEGORY_UI[blockData.category] || CATEGORY_UI.ETC;

  return (
    <div
      className="travel-block"
      style={{
        backgroundColor: ui.color,
        padding: "10px",
        borderRadius: "8px",
        cursor: "pointer",
      }}
      onClick={() => onClickEdit(blockData)}
    >
      <div className="block-header" style={{ fontWeight: "bold" }}>
        <span>{ui.icon}</span>
        <span style={{ marginLeft: "8px" }}>
          {blockData.startTime} - {blockData.endTime} ({blockData.durationMin}
          분)
        </span>
        {blockData.isTimeFixed && (
          <span style={{ marginLeft: "auto" }}>🔒</span>
        )}
      </div>

      <div>
        <h4>{blockData.name}</h4>
        {blockData.address && (
          <p style={{ fontSize: "12px" }}>{blockData.address}</p>
        )}
      </div>
    </div>
  );
}
