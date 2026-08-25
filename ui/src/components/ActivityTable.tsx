import { CheckCircle2, CircleAlert, Clock3, MoreHorizontal } from 'lucide-react';

export type ActivityItem = {
  id: string;
  action: string;
  subject: string;
  actor: string;
  time: string;
  status: 'success' | 'pending' | 'warning';
};

const statusConfig = {
  success: { label: '已完成', icon: CheckCircle2, className: 'status-success' },
  pending: { label: '处理中', icon: Clock3, className: 'status-pending' },
  warning: { label: '需关注', icon: CircleAlert, className: 'status-warning' },
};

type ActivityTableProps = {
  items: ActivityItem[];
};

export function ActivityTable({ items }: ActivityTableProps) {
  return (
    <div className="table-shell">
      <table className="activity-table">
        <thead>
          <tr>
            <th>操作</th>
            <th>发起人</th>
            <th>时间</th>
            <th>状态</th>
            <th><span className="sr-only">更多操作</span></th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const status = statusConfig[item.status];
            const StatusIcon = status.icon;
            return (
              <tr key={item.id}>
                <td>
                  <div className="activity-action">
                    <strong>{item.action}</strong>
                    <span>{item.subject}</span>
                  </div>
                </td>
                <td><span className="actor-cell"><span className="mini-avatar">{item.actor.slice(0, 1)}</span>{item.actor}</span></td>
                <td className="time-cell">{item.time}</td>
                <td><span className={`status-badge ${status.className}`}><StatusIcon size={14} />{status.label}</span></td>
                <td><button className="table-more" type="button" aria-label={`查看 ${item.action} 详情`}><MoreHorizontal size={17} /></button></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
