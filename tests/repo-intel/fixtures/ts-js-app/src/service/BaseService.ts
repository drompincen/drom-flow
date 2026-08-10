/** Base class for domain services. */

export class BaseService {
  protected serviceName: string;

  constructor(name: string) {
    this.serviceName = name;
  }

  getName(): string {
    return this.serviceName;
  }

  protected log(message: string): void {
    // intentional no-op for fixture
    void message;
  }
}
