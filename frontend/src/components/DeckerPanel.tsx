import { type DeckerStateDto } from '../types/messages'

interface Props {
  decker: DeckerStateDto
}

function DamageMonitor({
  label,
  damage,
  maxBoxes,
}: {
  label: string
  damage: number
  maxBoxes: number
}) {
  return (
    <div className="damage-monitor">
      <span className="monitor-label">{label}</span>
      <div className="monitor-boxes">
        {Array.from({ length: maxBoxes }, (_, i) => (
          <span key={i} className={`monitor-box ${i < damage ? 'damaged' : 'healthy'}`}>
            {i < damage ? '■' : '□'}
          </span>
        ))}
      </div>
      <span className="monitor-count">
        {damage}/{maxBoxes}
      </span>
    </div>
  )
}

export default function DeckerPanel({ decker }: Props) {
  return (
    <div className="panel decker-panel">
      <div className="panel-header">DECKER</div>
      <div className="panel-body">
        <div className="stat-value" style={{ fontSize: 24, letterSpacing: 2, marginBottom: 4 }}>
          {decker.name}
        </div>
        {decker.isPinnedByBlackIc && (
          <div className="pinned-alert">⚠ PINNED BY BLACK IC</div>
        )}

        <DamageMonitor label="PHYS" damage={decker.physicalDamage} maxBoxes={decker.physicalMaxBoxes} />
        <DamageMonitor label="MENT" damage={decker.mentalDamage} maxBoxes={decker.mentalMaxBoxes} />

        <div className="stat-row">
          <span className="stat-label">HACKING POOL</span>
          <span className="stat-value">{decker.hackingPool}d</span>
        </div>
        <div className="stat-row">
          <span className="stat-label">MCP RATING</span>
          <span className="stat-value">{decker.mcpRating}</span>
        </div>

        {decker.activeUtilities.length > 0 && (
          <>
            <div className="section-title">LOADED PROGRAMS</div>
            <div>
              {decker.activeUtilities.map((u, i) => (
                <div key={i} className="program-row">
                  <span className="program-name">{u.type}</span>
                  <span className="program-rating">
                    {'●'.repeat(Math.min(u.rating, 10))}
                    {'○'.repeat(Math.max(0, 10 - u.rating))} ({u.rating})
                  </span>
                </div>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  )
}
